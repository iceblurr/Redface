/*
 * Copyright 2015 Ayuget
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ayuget.redface.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.customtabs.CustomTabsIntent;
import android.support.v4.content.ContextCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;

import com.ayuget.redface.BuildConfig;
import com.ayuget.redface.RedfaceApp;
import com.ayuget.redface.R;
import com.ayuget.redface.settings.RedfaceSettings;
import com.ayuget.redface.ui.customtabs.CustomTabActivityHelper;
import com.ayuget.redface.ui.misc.ThemeManager;
import com.ayuget.redface.ui.misc.UiUtils;
import com.ayuget.redface.util.ViewServer;
import io.fabric.sdk.android.Fabric;

import com.crashlytics.android.Crashlytics;
import com.squareup.otto.Bus;

import javax.inject.Inject;

import butterknife.ButterKnife;
import rx.Subscription;
import rx.subscriptions.CompositeSubscription;


public class BaseActivity extends AppCompatActivity {
    @Inject
    RedfaceSettings settings;

    @Inject
    Bus bus;

    @Inject
    ThemeManager themeManager;

    private CompositeSubscription subscriptions;

    private CustomTabActivityHelper customTab;

    /**
     * Dummy callback, no necessary warmup for now
     */
    private final CustomTabActivityHelper.ConnectionCallback customTabConnect
            = new CustomTabActivityHelper.ConnectionCallback() {
        @Override public void onCustomTabsConnected() { }

        @Override public void onCustomTabsDisconnected() { }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Fabric.with(this, new Crashlytics());

        RedfaceApp app = RedfaceApp.get(this);
        app.inject(this);

        initializeTheme();

        // Proper RxJava subscriptions management with CompositeSubscription
        subscriptions = new CompositeSubscription();

        // Necessary to inspect view hierarchy
        if (BuildConfig.DEBUG) {
            ViewServer.get(this).addWindow(this);
        }

        customTab = new CustomTabActivityHelper();
        customTab.setConnectionCallback(customTabConnect);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Proper RxJava subscriptions management with CompositeSubscription
        subscriptions = new CompositeSubscription();
        bus.register(this);
        customTab.bindCustomTabsService(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        bus.unregister(this);
        customTab.unbindCustomTabsService(this);
        subscriptions.unsubscribe();
    }



    @Override
    protected void onResume() {
        super.onResume();

        if (themeManager.isRefreshNeeded()) {
            themeManager.setRefreshNeeded(false);
            refreshTheme();
        }

        if (BuildConfig.DEBUG) {
            ViewServer.get(this).setFocusedWindow(this);
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        ButterKnife.inject(this);
    }

    /**
     * Sets the content view and calls the appropriate Ui initialization callbacks
     * based on the saved instance state
     */
    public void setContentView(int layoutResID, Bundle savedInstanceState) {
        super.setContentView(layoutResID);
        ButterKnife.inject(this);

        onInitUiState();

        if (savedInstanceState == null) {
            onSetupUiState();
        }
        else {
            onRestoreUiState(savedInstanceState);
        }
    }

    /**
     * Initializes UI state. Always called when setContentView(layoutResID, savedInstanceState) is called
     */
    protected void onInitUiState() {
    }

    /**
     * Sets up UI state, called if no saved instance state was provided when the activity was created
     */
    protected void onSetupUiState() {
    }

    /**
     * Custom callback to restore (mostly fragments) state, because onRestoreInstanceState() is called
     * too late in the activity lifecycle
     * @param savedInstanceState saved state
     */
    protected void onRestoreUiState(Bundle savedInstanceState) {
    }

    @Override
    protected void onDestroy() {
        customTab.setConnectionCallback(null);

        super.onDestroy();

        if (BuildConfig.DEBUG) {
            ViewServer.get(this).removeWindow(this);
        }
    }

    protected void initializeTheme() {
        getWindow().setBackgroundDrawable(null);
        setTheme(themeManager.getActiveThemeStyle());
    }

    protected void subscribe(Subscription s) {
        subscriptions.add(s);
    }

    public void refreshTheme() {
        finish();
        Intent intent = new Intent(this, TopicsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    public DrawerLayout getDrawerLayout() {
        return (DrawerLayout) findViewById(R.id.hfr_drawer_layout);
    }

    public RedfaceSettings getSettings() {
        return settings;
    }

    public void openLink(String url) {
        if (settings.isInternalBrowserEnabled()) {
            CustomTabActivityHelper.openCustomTab(
                    this,
                    new CustomTabsIntent.Builder()
                            .setToolbarColor(UiUtils.getInternalBrowserToolbarColor(this))
                            .build(),
                    Uri.parse(url));
        }
        else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }
}
