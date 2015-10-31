package eu.faircode.netguard;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.android.vending.billing.IInAppBillingService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ActivityMain extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Main";

    private boolean running = false;
    private SwipeRefreshLayout swipeRefresh;
    private RuleAdapter adapter = null;
    private MenuItem menuSearch = null;
    private MenuItem menuNetwork = null;
    private IInAppBillingService billingService = null;

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_DONATION = 2;

    // adb shell pm clear com.android.vending
    private static final String SKU_DONATE = "donation"; // "android.test.purchased";
    private static final String ACTION_IAB = "eu.faircode.netguard.IAB";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "Create");

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setTheme(prefs.getBoolean("dark_theme", false) ? R.style.AppThemeDark : R.style.AppTheme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        running = true;
        boolean enabled = prefs.getBoolean("enabled", false);

        // Action bar
        View view = getLayoutInflater().inflate(R.layout.actionbar, null);
        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setCustomView(view);

        // On/off switch
        SwitchCompat swEnabled = (SwitchCompat) view.findViewById(R.id.swEnabled);
        swEnabled.setChecked(enabled);
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Log.i(TAG, "Switch on");
                    Intent prepare = VpnService.prepare(ActivityMain.this);
                    if (prepare == null) {
                        Log.e(TAG, "Prepare done");
                        onActivityResult(REQUEST_VPN, RESULT_OK, null);
                    } else {
                        Log.i(TAG, "Start intent=" + prepare);
                        try {
                            startActivityForResult(prepare, REQUEST_VPN);
                        } catch (Throwable ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                            onActivityResult(REQUEST_VPN, RESULT_CANCELED, null);
                            Toast.makeText(ActivityMain.this, ex.toString(), Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    Log.i(TAG, "Switch off");
                    prefs.edit().putBoolean("enabled", false).apply();
                    SinkholeService.stop(ActivityMain.this);
                }
            }
        });

        // Disabled warning
        TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
        tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);

        // Application list
        RecyclerView rvApplication = (RecyclerView) findViewById(R.id.rvApplication);
        rvApplication.setHasFixedSize(true);
        rvApplication.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter(ActivityMain.this);
        rvApplication.setAdapter(adapter);

        // Swipe to refresh
        swipeRefresh = (SwipeRefreshLayout) findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(Color.WHITE, Color.WHITE, Color.WHITE);
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.colorPrimary);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                updateApplicationList();
            }
        });

        // Fill application list
        updateApplicationList();

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Listen for connectivity updates
        IntentFilter ifConnectivity = new IntentFilter();
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityChangedReceiver, ifConnectivity);

        // Listen for added/removed applications
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        registerReceiver(packageChangedReceiver, intentFilter);

        // Connect to billing
        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        serviceIntent.setPackage("com.android.vending");
        bindService(serviceIntent, billingConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");
        running = false;

        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(connectivityChangedReceiver);
        unregisterReceiver(packageChangedReceiver);

        if (billingConnection != null)
            unbindService(billingConnection);

        super.onDestroy();
    }

    private BroadcastReceiver connectivityChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(TAG, intent);
            if (menuNetwork != null)
                menuNetwork.setIcon(Util.isWifiActive(ActivityMain.this) ? R.drawable.ic_network_wifi_white_24dp : R.drawable.ic_network_cell_white_24dp);
        }
    };

    private BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(TAG, intent);
            updateApplicationList();
        }
    };

    private ServiceConnection billingConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Billing disconnected");
            billingService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Billing connected");
            billingService = IInAppBillingService.Stub.asInterface(service);
        }
    };

    private void updateApplicationList() {

        new AsyncTask<Object, Object, List<Rule>>() {
            @Override
            protected void onPreExecute() {
                swipeRefresh.post(new Runnable() {
                    @Override
                    public void run() {
                        swipeRefresh.setRefreshing(true);
                    }
                });
            }

            @Override
            protected List<Rule> doInBackground(Object... arg) {
                return Rule.getRules(false, ActivityMain.this);
            }

            @Override
            protected void onPostExecute(List<Rule> result) {
                if (running) {
                    if (menuSearch != null)
                        MenuItemCompat.collapseActionView(menuSearch);
                    if (adapter != null)
                        adapter.set(result);
                    if (swipeRefresh != null)
                        swipeRefresh.setRefreshing(false);
                }
            }
        }.execute();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name));
        if ("enabled".equals(name)) {
            // Get enabled
            boolean enabled = prefs.getBoolean(name, false);

            // Display disabled warning
            TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
            tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);

            // Check switch state
            SwitchCompat swEnabled = (SwitchCompat) getSupportActionBar().getCustomView().findViewById(R.id.swEnabled);
            if (swEnabled.isChecked() != enabled)
                swEnabled.setChecked(enabled);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        // Search
        menuSearch = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(menuSearch);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (adapter != null)
                    adapter.getFilter().filter(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (adapter != null)
                    adapter.getFilter().filter(newText);
                return true;
            }
        });
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                if (adapter != null)
                    adapter.getFilter().filter(null);
                return true;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        menuNetwork = menu.findItem(R.id.menu_network);
        menuNetwork.setIcon(Util.isWifiActive(this) ? R.drawable.ic_network_wifi_white_24dp : R.drawable.ic_network_cell_white_24dp);

        MenuItem menuWifi = menu.findItem(R.id.menu_whitelist_wifi);
        menuWifi.setChecked(prefs.getBoolean("whitelist_wifi", true));

        MenuItem menuOther = menu.findItem(R.id.menu_whitelist_other);
        menuOther.setChecked(prefs.getBoolean("whitelist_other", true));

        MenuItem menuSystem = menu.findItem(R.id.menu_system);
        menuSystem.setChecked(prefs.getBoolean("manage_system", false));

        MenuItem menuTheme = menu.findItem(R.id.menu_theme);
        menuTheme.setChecked(prefs.getBoolean("dark_theme", false));

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menu_network:
                menu_network();
                return true;

            case R.id.menu_whitelist_wifi:
                menu_whitelist_wifi(prefs);
                return true;

            case R.id.menu_whitelist_other:
                menu_whitelist_other(prefs);
                return true;

            case R.id.menu_system:
                menu_system(prefs);
                return true;

            case R.id.menu_theme:
                menu_theme(prefs);
                return true;

            case R.id.menu_vpn_settings:
                menu_vpn_settings();
                return true;

            case R.id.menu_support:
                menu_support();
                return true;

            case R.id.menu_about:
                menu_about();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void menu_network() {
        Intent settings = new Intent(Util.isWifiActive(this)
                ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_WIRELESS_SETTINGS);
        if (settings.resolveActivity(getPackageManager()) != null)
            startActivity(settings);
        else
            Log.w(TAG, settings + " not available");
    }

    private void menu_whitelist_wifi(SharedPreferences prefs) {
        prefs.edit().putBoolean("whitelist_wifi", !prefs.getBoolean("whitelist_wifi", true)).apply();
        updateApplicationList();
        SinkholeService.reload("wifi", this);
    }

    private void menu_whitelist_other(SharedPreferences prefs) {
        prefs.edit().putBoolean("whitelist_other", !prefs.getBoolean("whitelist_other", true)).apply();
        updateApplicationList();
        SinkholeService.reload("other", this);
    }

    private void menu_system(SharedPreferences prefs) {
        prefs.edit().putBoolean("manage_system", !prefs.getBoolean("manage_system", true)).apply();
        updateApplicationList();
        SinkholeService.reload(null, this);
    }

    private void menu_theme(SharedPreferences prefs) {
        prefs.edit().putBoolean("dark_theme", !prefs.getBoolean("dark_theme", false)).apply();
        recreate();
    }

    private void menu_vpn_settings() {
        Intent vpn = new Intent("android.net.vpn.SETTINGS");
        if (vpn.resolveActivity(getPackageManager()) != null)
            startActivity(vpn);
        else
            Log.w(TAG, vpn + " not available");
    }

    private void menu_support() {
        Intent xda = new Intent(Intent.ACTION_VIEW);
        xda.setData(Uri.parse("http://forum.xda-developers.com/showthread.php?t=3233012"));
        if (xda.resolveActivity(getPackageManager()) != null)
            startActivity(xda);
        else
            Log.w(TAG, xda + " not available");
    }

    private void menu_about() {
        final boolean valid = Util.hasValidFingerprint(TAG, this);

        // Create view
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.about, null);
        TextView tvVersion = (TextView) view.findViewById(R.id.tvVersion);
        final Button btnDonate = (Button) view.findViewById(R.id.btnDonate);
        final TextView tvThanks = (TextView) view.findViewById(R.id.tvThanks);

        // Show version
        tvVersion.setText(Util.getSelfVersionName(this));

        // Handle logcat
        if (valid)
            view.setOnClickListener(new View.OnClickListener() {
                private short tap = 0;

                @Override
                public void onClick(View view) {
                    if (++tap == 7) {
                        tap = 0;
                        Util.sendLogcat(TAG, ActivityMain.this);
                    }
                }
            });

        // Handle donate
        final Intent donate = new Intent(Intent.ACTION_VIEW);
        donate.setData(Uri.parse("http://www.netguard.me/"));
        btnDonate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (valid && billingService != null)
                    IABinitiate();
                else
                    startActivity(donate);
            }
        });

        // Handle donated
        final BroadcastReceiver onIABsuccess = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                btnDonate.setVisibility(View.GONE);
                tvThanks.setVisibility(View.VISIBLE);
            }
        };
        IntentFilter iff = new IntentFilter(ACTION_IAB);
        LocalBroadcastManager.getInstance(this).registerReceiver(onIABsuccess, iff);

        // Show dialog
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        LocalBroadcastManager.getInstance(ActivityMain.this).unregisterReceiver(onIABsuccess);
                    }
                })
                .create();
        dialog.show();

        // Validate IAB
        if (valid && billingService != null)
            new AsyncTask<Object, Object, Boolean>() {
                @Override
                protected Boolean doInBackground(Object... objects) {
                    return IABvalidate();
                }

                @Override
                protected void onPostExecute(Boolean ok) {
                    btnDonate.setVisibility(ok ? View.GONE : View.VISIBLE);
                    tvThanks.setVisibility(ok ? View.VISIBLE : View.GONE);
                }
            }.execute();
        else
            btnDonate.setVisibility(donate.resolveActivity(getPackageManager()) == null ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));
        if (data != null)
            Util.logExtras(TAG, data);

        if (requestCode == REQUEST_VPN) {
            // Update enabled state
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", resultCode == RESULT_OK).apply();

            // Start service
            if (resultCode == RESULT_OK)
                SinkholeService.start(this);

        } else if (requestCode == REQUEST_DONATION) {
            if (resultCode == RESULT_OK) {
                // Handle donation
                Intent intent = new Intent(ACTION_IAB);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            } else if (data != null) {
                int response = data.getIntExtra("RESPONSE_CODE", -1);
                Log.i(TAG, "Billing response=" + getIABResult(response));
            }

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private boolean IABvalidate() {
        try {
            // Get available SKUs
            ArrayList<String> skuList = new ArrayList<>();
            skuList.add(SKU_DONATE);
            Bundle query = new Bundle();
            query.putStringArrayList("ITEM_ID_LIST", skuList);
            Bundle details = billingService.getSkuDetails(3, getPackageName(), "inapp", query);
            Log.i(TAG, "Billing.getSkuDetails");
            Util.logBundle(TAG, details);
            int details_response = details.getInt("RESPONSE_CODE");
            Log.i(TAG, "Billing response=" + getIABResult(details_response));
            if (details_response != 0)
                return false;

            // Check available SKUs
            boolean found = false;
            for (String item : details.getStringArrayList("DETAILS_LIST")) {
                JSONObject object = new JSONObject(item);
                if (SKU_DONATE.equals(object.getString("productId"))) {
                    found = true;
                    break;
                }
            }
            Log.i(TAG, SKU_DONATE + "=" + found);
            if (!found)
                return false;

            // Get purchases
            Bundle purchases = billingService.getPurchases(3, getPackageName(), "inapp", null);
            Log.i(TAG, "Billing.getPurchases");
            Util.logBundle(TAG, purchases);
            int purchases_response = purchases.getInt("RESPONSE_CODE");
            Log.i(TAG, "Billing response=" + getIABResult(purchases_response));
            if (purchases_response != 0)
                return false;

            // Check purchases
            ArrayList<String> skus = purchases.getStringArrayList("INAPP_PURCHASE_ITEM_LIST");
            return skus.contains(SKU_DONATE);

        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return false;
        }
    }

    private void IABinitiate() {
        try {
            Bundle bundle = billingService.getBuyIntent(3, getPackageName(), SKU_DONATE, "inapp", "");
            Log.i(TAG, "Billing.getBuyIntent");
            Util.logBundle(TAG, bundle);
            int response = bundle.getInt("RESPONSE_CODE");
            Log.i(TAG, "Billing response=" + getIABResult(response));
            if (response == 0) {
                PendingIntent pi = bundle.getParcelable("BUY_INTENT");
                startIntentSenderForResult(
                        pi.getIntentSender(),
                        REQUEST_DONATION,
                        new Intent(),
                        Integer.valueOf(0),
                        Integer.valueOf(0),
                        Integer.valueOf(0));
            }
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            Toast.makeText(ActivityMain.this, ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private static String getIABResult(int responseCode) {
        switch (responseCode) {
            case 0:
                return "BILLING_RESPONSE_RESULT_OK";
            case 1:
                return "BILLING_RESPONSE_RESULT_USER_CANCELED";
            case 2:
                return "BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE";
            case 3:
                return "BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE";
            case 4:
                return "BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE";
            case 5:
                return "BILLING_RESPONSE_RESULT_DEVELOPER_ERROR";
            case 6:
                return "BILLING_RESPONSE_RESULT_ERROR";
            case 7:
                return "BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED";
            case 8:
                return "BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED";
            default:
                return Integer.toString(responseCode);
        }
    }
}
