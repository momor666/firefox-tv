/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.fragment;

import android.app.Activity;
import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import org.mozilla.focus.R;
import org.mozilla.focus.activity.InfoActivity;
import org.mozilla.focus.activity.InstallFirefoxActivity;
import org.mozilla.focus.activity.MainActivity;
import org.mozilla.focus.architecture.NonNullObserver;
import org.mozilla.focus.ext.ContextKt;
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity;
import org.mozilla.focus.menu.browser.BrowserMenu;
import org.mozilla.focus.open.OpenWithFragment;
import org.mozilla.focus.session.NullSession;
import org.mozilla.focus.session.Session;
import org.mozilla.focus.session.SessionCallbackProxy;
import org.mozilla.focus.session.SessionManager;
import org.mozilla.focus.telemetry.TelemetryWrapper;
import org.mozilla.focus.utils.Browsers;
import org.mozilla.focus.utils.Direction;
import org.mozilla.focus.utils.Edge;
import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.widget.AnimatedProgressBar;
import org.mozilla.focus.widget.Cursor;
import org.mozilla.focus.widget.CursorEvent;

import java.lang.ref.WeakReference;

/**
 * Fragment for displaying the browser UI.
 */
public class BrowserFragment extends WebFragment implements View.OnClickListener, CursorEvent {
    public static final String FRAGMENT_TAG = "browser";

    private static final int ANIMATION_DURATION = 300;

    private static final String ARGUMENT_SESSION_UUID = "sessionUUID";
    private static final int SCROLL_MULTIPLIER = 45;

    public static BrowserFragment createForSession(Session session) {
        final Bundle arguments = new Bundle();
        arguments.putString(ARGUMENT_SESSION_UUID, session.getUUID());

        BrowserFragment fragment = new BrowserFragment();
        fragment.setArguments(arguments);

        return fragment;
    }

    private TextView urlView;
    private AnimatedProgressBar progressView;
    private ImageView lockView;
    private Cursor cursor;
    private WeakReference<BrowserMenu> menuWeakReference = new WeakReference<>(null);

    /**
     * Container for custom video views shown in fullscreen mode.
     */
    private ViewGroup videoContainer;

    /**
     * Container containing the browser chrome and web content.
     */
    private View browserContainer;

    private View forwardButton;
    private View backButton;
    private View refreshButton;
    private View stopButton;

    private IWebView.FullscreenCallback fullscreenCallback;

    private SessionManager sessionManager;
    private Session session;

    public BrowserFragment() {
        sessionManager = SessionManager.getInstance();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String sessionUUID = getArguments().getString(ARGUMENT_SESSION_UUID);
        if (sessionUUID == null) {
            throw new IllegalAccessError("No session exists");
        }

        session = sessionManager.hasSessionWithUUID(sessionUUID)
                ? sessionManager.getSessionByUUID(sessionUUID)
                : new NullSession();

        session.getBlockedTrackers().observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(@Nullable Integer blockedTrackers) {
                if (menuWeakReference == null) {
                    return;
                }

                final BrowserMenu menu = menuWeakReference.get();

                if (menu != null) {
                    //noinspection ConstantConditions - Not null
                    menu.updateTrackers(blockedTrackers);
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        final Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).updateHintNavigationVisibility(MainActivity.VideoPlayerState.BROWSER);
        }
    }

    public Session getSession() {
        return session;
    }

    @Override
    public String getInitialUrl() {
        return session.getUrl().getValue();
    }

    @Override
    public void onPause() {
        super.onPause();

        final BrowserMenu menu = menuWeakReference.get();
        if (menu != null) {
            menu.dismiss();

            menuWeakReference.clear();
        }
    }

    @Override
    public View inflateLayout(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_browser, container, false);

        cursor = (Cursor) view.findViewById(R.id.cursor);
        cursor.cursorEvent = this;

        videoContainer = (ViewGroup) view.findViewById(R.id.video_container);
        browserContainer = view.findViewById(R.id.browser_container);

        urlView = (TextView) view.findViewById(R.id.display_url);

        progressView = (AnimatedProgressBar) view.findViewById(R.id.progress);

        session.getUrl().observe(this, new Observer<String>() {
            @Override
            public void onChanged(@Nullable String url) {
                urlView.setText(UrlUtils.stripUserInfo(url));
            }
        });

        setBlockingEnabled(session.isBlockingEnabled());

        session.getLoading().observe(this, new NonNullObserver<Boolean>() {
            @Override
            public void onValueChanged(@NonNull Boolean loading) {
                if (loading) {
                    progressView.setProgress(5);
                    progressView.setVisibility(View.VISIBLE);
                } else {
                    if (progressView.getVisibility() == View.VISIBLE) {
                        // We start a transition only if a page was just loading before
                        // allowing to avoid issue #1179
                        progressView.setProgress(progressView.getMax());
                        progressView.setVisibility(View.GONE);
                    }
                }

                updateToolbarButtonStates(loading);

                final BrowserMenu menu = menuWeakReference.get();
                if (menu != null) {
                    menu.updateLoading(loading);
                }

                final MainActivity activity = (MainActivity)getActivity();
                updateCursorState();
            }
        });

        if ((refreshButton = view.findViewById(R.id.refresh)) != null) {
            refreshButton.setOnClickListener(this);
        }

        if ((stopButton = view.findViewById(R.id.stop)) != null) {
            stopButton.setOnClickListener(this);
        }

        if ((forwardButton = view.findViewById(R.id.forward)) != null) {
            forwardButton.setOnClickListener(this);
        }

        if ((backButton = view.findViewById(R.id.back)) != null) {
            backButton.setOnClickListener(this);
        }

        lockView = (ImageView) view.findViewById(R.id.lock);
        session.getSecure().observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean secure) {
                lockView.setVisibility(secure ? View.VISIBLE : View.GONE);
            }
        });

        session.getProgress().observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer progress) {
                progressView.setProgress(progress);
            }
        });

        initialiseNormalBrowserUi(view);

        return view;
    }

    /**
     * Gets the current state of the application and updates the cursor state accordingly.
     *
     * Note that this pattern could use some improvements:
     * - It's a little weird to get the current state from globals, rather than get passed in relevant values.
     * - BrowserFragment.setCursorEnabled should be called from this code path, but that's unclear
     * - BrowserFragment should use a listener to talk to MainActivity and shouldn't know about it directly.
     * - BrowserFragment calls MainActivity which calls BrowserFragment again - this is unnecessary.
     */
    public void updateCursorState() {
        final MainActivity activity = (MainActivity)getActivity();
        final IWebView webView = getWebView();
        // Bandaid null checks, underlying issue #249
        final boolean enableCursor = webView != null &&
                webView.getUrl() != null &&
                !webView.getUrl().contains("youtube.com/tv") &&
                getContext() != null &&
                !ContextKt.isVoiceViewEnabled(getContext()); // VoiceView has its own navigation controls.
        activity.setCursorEnabled(enableCursor);
    }

    private void initialiseNormalBrowserUi(final @NonNull View view) {
        urlView.setOnClickListener(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    public IWebView.Callback createCallback() {
        return new SessionCallbackProxy(session, new IWebView.Callback() {
            @Override
            public void onPageStarted(final String url) {}

            @Override
            public void onPageFinished(boolean isSecure) {}

            @Override
            public void onURLChanged(final String url) {}

            @Override
            public void onRequest(boolean isTriggeredByUserGesture) {}

            @Override
            public void onProgress(int progress) {}

            @Override
            public void countBlockedTracker() {}

            @Override
            public void resetBlockedTrackers() {}

            @Override
            public void onBlockingStateChanged(boolean isBlockingEnabled) {}

            @Override
            public void onLongPress(final IWebView.HitTarget hitTarget) {}

            @Override
            public void onEnterFullScreen(@NonNull final IWebView.FullscreenCallback callback, @Nullable View view) {
                fullscreenCallback = callback;

                if (view != null) {
                    // Hide browser UI and web content
                    browserContainer.setVisibility(View.INVISIBLE);

                    // Add view to video container and make it visible
                    final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    videoContainer.addView(view, params);
                    videoContainer.setVisibility(View.VISIBLE);

                    // Switch to immersive mode: Hide system bars other UI controls
                    switchToImmersiveMode();
                }
            }

            @Override
            public void onExitFullScreen() {
                // Remove custom video views and hide container
                videoContainer.removeAllViews();
                videoContainer.setVisibility(View.GONE);

                // Show browser UI and web content again
                browserContainer.setVisibility(View.VISIBLE);

                exitImmersiveModeIfNeeded();

                // Notify renderer that we left fullscreen mode.
                if (fullscreenCallback != null) {
                    fullscreenCallback.fullScreenExited();
                    fullscreenCallback = null;
                }
            }
        });
    }

    /**
     * Hide system bars. They can be revealed temporarily with system gestures, such as swiping from
     * the top of the screen. These transient system bars will overlay app’s content, may have some
     * degree of transparency, and will automatically hide after a short timeout.
     */
    private void switchToImmersiveMode() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        final Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Show the system bars again.
     */
    private void exitImmersiveModeIfNeeded() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        if ((WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON & activity.getWindow().getAttributes().flags) == 0) {
            // We left immersive mode already.
            return;
        }

        final Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // This fragment might get destroyed before the user left immersive mode (e.g. by opening another URL from an app).
        // In this case let's leave immersive mode now when the fragment gets destroyed.
        exitImmersiveModeIfNeeded();
    }

    void showAddToHomescreenDialog(String url, String title) {
        final FragmentManager fragmentManager = getFragmentManager();

        if (fragmentManager.findFragmentByTag(AddToHomescreenDialogFragment.FRAGMENT_TAG) != null) {
            // We are already displaying a homescreen dialog fragment (Probably a restored fragment).
            // No need to show another one.
            return;
        }

        final AddToHomescreenDialogFragment addToHomescreenDialogFragment = AddToHomescreenDialogFragment.newInstance(url, title, session.isBlockingEnabled());
        addToHomescreenDialogFragment.setTargetFragment(BrowserFragment.this, 300);

        try {
            addToHomescreenDialogFragment.show(fragmentManager, AddToHomescreenDialogFragment.FRAGMENT_TAG);
        } catch (IllegalStateException e) {
            // It can happen that at this point in time the activity is already in the background
            // and onSaveInstanceState() has already been called. Fragment transactions are not
            // allowed after that anymore. It's probably safe to guess that the user might not
            // be interested in adding to homescreen now.
        }
    }

    public boolean onBackPressed() {
        if (canGoBack()) {
            // Go back in web history
            goBack();
        } else {
            getFragmentManager().popBackStack();
            SessionManager.getInstance().removeCurrentSession();
        }

        return true;
    }

    public void erase() {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.cleanup();
        }

        SessionManager.getInstance().removeCurrentSession();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.display_url: {
                // do nothing so don't crash.
                break;
            }

            case R.id.erase: {
                break;
            }

            case R.id.tabs:
                TelemetryWrapper.openTabsTrayEvent();
                break;

            case R.id.back: {
                goBack();
                break;
            }

            case R.id.forward: {
                goForward();
                break;
            }

            case R.id.refresh: {
                reload();

                TelemetryWrapper.menuReloadEvent();
                break;
            }

            case R.id.stop: {
                stop();
                break;
            }

            case R.id.share: {
                final String url = getUrl();
                final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, url);
                // Use title from webView if it's content matches the url
                final IWebView webView = getWebView();
                if (webView != null) {
                    final String contentUrl = webView.getUrl();
                    if (contentUrl != null && contentUrl.equals(url)) {
                        final String contentTitle = webView.getTitle();
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, contentTitle);
                    }
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)));

                TelemetryWrapper.shareEvent();
                break;
            }

            case R.id.settings:
                ((LocaleAwareAppCompatActivity) getActivity()).openPreferences();
                break;

            case R.id.open_default: {
                final Browsers browsers = new Browsers(getContext(), getUrl());

                final ActivityInfo defaultBrowser = browsers.getDefaultBrowser();

                if (defaultBrowser == null) {
                    // We only add this menu item when a third party default exists, in
                    // BrowserMenuAdapter.initializeMenu()
                    throw new IllegalStateException("<Open with $Default> was shown when no default browser is set");
                }

                final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getUrl()));
                intent.setPackage(defaultBrowser.packageName);
                startActivity(intent);

                if (browsers.isFirefoxDefaultBrowser()) {
                    TelemetryWrapper.openFirefoxEvent();
                } else {
                    TelemetryWrapper.openDefaultAppEvent();
                }
                break;
            }

            case R.id.open_select_browser: {
                final Browsers browsers = new Browsers(getContext(), getUrl());

                final ActivityInfo[] apps = browsers.getInstalledBrowsers();
                final ActivityInfo store = browsers.hasFirefoxBrandedBrowserInstalled()
                        ? null
                        : InstallFirefoxActivity.resolveAppStore(getContext());

                final OpenWithFragment fragment = OpenWithFragment.newInstance(
                        apps,
                        getUrl(),
                        store);
                fragment.show(getFragmentManager(), OpenWithFragment.FRAGMENT_TAG);

                TelemetryWrapper.openSelectionEvent();
                break;
            }

            case R.id.customtab_close: {
                erase();
                getActivity().finish();

                TelemetryWrapper.closeCustomTabEvent();
                break;
            }

            case R.id.help:
                Intent helpIntent = InfoActivity.getHelpIntent(getActivity());
                startActivity(helpIntent);
                break;

            case R.id.help_trackers:
                Intent trackerHelpIntent = InfoActivity.getTrackerHelpIntent(getActivity());
                startActivity(trackerHelpIntent);
                break;

            case R.id.add_to_homescreen:
                final IWebView webView = getWebView();
                if (webView == null) {
                    break;
                }

                final String url = webView.getUrl();
                final String title = webView.getTitle();
                showAddToHomescreenDialog(url, title);
                break;

            default:
                throw new IllegalArgumentException("Unhandled menu item in BrowserFragment");
        }
    }

    private void updateToolbarButtonStates(boolean isLoading) {
        if (forwardButton == null || backButton == null || refreshButton == null || stopButton == null) {
            return;
        }

        final IWebView webView = getWebView();
        if (webView == null) {
            return;
        }

        final boolean canGoForward = webView.canGoForward();
        final boolean canGoBack = webView.canGoBack();

        forwardButton.setEnabled(canGoForward);
        forwardButton.setAlpha(canGoForward ? 1.0f : 0.5f);
        backButton.setEnabled(canGoBack);
        backButton.setAlpha(canGoBack ? 1.0f : 0.5f);

        refreshButton.setVisibility(isLoading ? View.GONE : View.VISIBLE);
        stopButton.setVisibility(isLoading ? View.VISIBLE : View.GONE);
    }

    @NonNull
    public String getUrl() {
        // getUrl() is used for things like sharing the current URL. We could try to use the webview,
        // but sometimes it's null, and sometimes it returns a null URL. Sometimes it returns a data:
        // URL for error pages. The URL we show in the toolbar is (A) always correct and (B) what the
        // user is probably expecting to share, so lets use that here:
        return urlView.getText().toString();
    }

    public boolean canGoForward() {
        final IWebView webView = getWebView();
        return webView != null && webView.canGoForward();
    }

    public boolean canGoBack() {
        final IWebView webView = getWebView();
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.goBack();
        }
    }

    public void goForward() {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.goForward();
        }
    }

    public void loadUrl(final String url) {
        final IWebView webView = getWebView();
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url);
        }
    }

    public void reload() {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.reload();
        }
    }

    public void stop() {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.stopLoading();
        }
    }

    public void setBlockingEnabled(boolean enabled) {
        final IWebView webView = getWebView();
        if (webView != null) {
            webView.setBlockingEnabled(enabled);
        }
    }

    public void moveCursor(Direction direction) {
        cursor.moveCursor(direction);
    }

    public void stopMoving(Direction direction) {
        cursor.stopMoving(direction);
    }

    public Point getCursorLocation() {
        return cursor.getLocation();
    }

    public void setCursorEnabled(boolean toEnable) {
        cursor.setVisibility(toEnable ? View.VISIBLE : View.GONE);
    }

    private int getScrollVelocity() {
        int speed = (int)cursor.getSpeed();
        return speed * SCROLL_MULTIPLIER;
    }

    public void cursorHitEdge(Edge edge) {
        IWebView webView = getWebView();
        if (webView == null) {
            return;
        }

        switch (edge) {
            case TOP:
                webView.flingScroll(0, -getScrollVelocity());
                break;
            case BOTTOM:
                webView.flingScroll(0, getScrollVelocity());
                break;
            case LEFT:
                webView.flingScroll(-getScrollVelocity(), 0);
                break;
            case RIGHT:
                webView.flingScroll(getScrollVelocity(), 0);
                break;
        }
    }
}
