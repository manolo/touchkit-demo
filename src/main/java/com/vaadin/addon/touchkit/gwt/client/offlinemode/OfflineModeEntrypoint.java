package com.vaadin.addon.touchkit.gwt.client.offlinemode;

import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.APP_STARTING;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.BAD_RESPONSE;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.FORCE_OFFLINE;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.FORCE_ONLINE;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.NETWORK_ONLINE;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.NO_NETWORK;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.ONLINE_APP_NOT_STARTED;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.RESPONSE_TIMEOUT;
import static com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason.SERVER_AVAILABLE;

import java.util.logging.Logger;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;
import com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason;
import com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.OfflineEvent;
import com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.OnlineEvent;
import com.vaadin.addon.touchkit.gwt.client.vcom.OfflineModeConnector;
import com.vaadin.client.ApplicationConnection;
import com.vaadin.client.ApplicationConnection.CommunicationErrorHandler;
import com.vaadin.client.ApplicationConnection.CommunicationHandler;
import com.vaadin.client.ApplicationConnection.ConnectionStatusEvent;
import com.vaadin.client.ApplicationConnection.ConnectionStatusEvent.ConnectionStatusHandler;
import com.vaadin.client.ApplicationConnection.RequestStartingEvent;
import com.vaadin.client.ApplicationConnection.ResponseHandlingEndedEvent;
import com.vaadin.client.ApplicationConnection.ResponseHandlingStartedEvent;

/**
 * When this entry point starts an OfflineMode application is started.
 *
 * When the online application goes available, it deactivates the
 * offline application.
 *
 * It listen for HTML5 and Cordova online/off-line events activating/deactivating
 * the offline app.
 *
 * It also observes any request to check whether the server goes unreachable, and
 * reconfigures heartbeat intervals depending on the connection status.
 */
public class OfflineModeEntrypoint implements EntryPoint, CommunicationHandler,
        CommunicationErrorHandler, ConnectionStatusHandler, RequestCallback {

    private static OfflineModeEntrypoint instance = null;
    private static OfflineMode offlineModeApp = GWT.create(OfflineMode.class);
    private static boolean online = true;

    private OfflineModeConnector offlineModeConnector = null;
    private final Logger logger = Logger.getLogger(this.getClass().getName());

    private boolean forcedOffline = false;
    private boolean serverAvailable = false;
    private boolean networkOnline = true;

    private ActivationReason lastReason = null;
    private ApplicationConnection applicationConnection = null;
    private int hbeatInterval = 60000;
    private int pingTimeout = 10000;

    /**
     * @return whether the network is online and server reachable.
     */
    public static boolean isNetworkOnline() {
        return online;
    }

    /**
     * @return the singletone instance of the OfflineModeEntrypoint
     */
    public static OfflineModeEntrypoint get() {
        // Shoulden't happen unless someone does not inherits TK module
        if (instance == null) {
            new OfflineModeEntrypoint().onModuleLoad();
        }
        return instance;
    }

    private final Timer pingToServer = new Timer() {
        final String url = GWT.getHostPageBaseURL() + "PING";
        public void run() {
            if (networkOnline) {
                RequestBuilder rq = new RequestBuilder(RequestBuilder.POST, url);
                rq.setTimeoutMillis(pingTimeout);
                rq.setCallback(OfflineModeEntrypoint.this);
                try {
                    logger.fine("Sending a ping request to the server.");
                    rq.send();
                } catch (Exception e) {
                    onError(null, e);
                }
            }
        }
    };

    @Override
    public void onModuleLoad() {
        // Do not run twice.
        if (instance != null) {
            return;
        }
        instance = this;

        // Configure HTML5 off-line listeners
        configureApplicationOfflineEvents();

        // We always go off-line at the beginning until we receive
        // a Vaadin online response
        dispatch(APP_STARTING);
    }

    /**
     * Set the offlineModeConnector when the online Vaadin app starts.
     */
    public void setOfflineModeConnector(OfflineModeConnector oc) {
        offlineModeConnector = oc;
        applicationConnection = oc.getConnection();
        applicationConnection.addHandler(RequestStartingEvent.TYPE, this);
        applicationConnection.addHandler(ResponseHandlingStartedEvent.TYPE, this);
        applicationConnection.addHandler(ResponseHandlingEndedEvent.TYPE, this);
        applicationConnection.addHandler(ConnectionStatusEvent.TYPE, this);
        applicationConnection.setCommunicationErrorDelegate(this);

        // If we get the connection, it means we are online and the server
        // is available, so we go online although we already were.
        online = false;
        dispatch(SERVER_AVAILABLE);
    }

    /**
     * Receive any network event, set the appropriate flags and
     * go Off-line or On-line in case.
     */
    public void dispatch(ActivationReason reason) {
        logger.info("Dispatching: " + lastReason + " -> " + reason);
        if (lastReason != reason) {
            if (reason == NETWORK_ONLINE) {
                if (!networkOnline) {
                    networkOnline = true;
                    // Don't goOnline yet, it's better to ping
                    // the server previously.
                    ping();
                }
                networkOnline = true;
            } else if (reason == NO_NETWORK) {
                networkOnline = false;
                if (serverAvailable || lastReason == APP_STARTING) {
                    goOffline(reason);
                }
            } else if (reason == SERVER_AVAILABLE) {
                serverAvailable = true;
                networkOnline = true;
                goOnline(reason);
            } else if (reason == FORCE_OFFLINE) {
                forcedOffline = true;
                goOffline(reason);
            } else if (reason == FORCE_ONLINE) {
                forcedOffline = false;
                ping();
            } else {
                serverAvailable = false;
                goOffline(reason);
            }
        }
    }

    /*
     * Configure application heartbeat depending on the status.
     * If application is not ready we use a timer instead.
     */
    private void configureHeartBeat() {
        if (online) {
            if (applicationConnection != null) {
                applicationConnection.getHeartbeat().setInterval(hbeatInterval / 1000);
            } else {
                pingToServer.schedule(hbeatInterval);
            }
        } else {
            if (applicationConnection != null) {
                if (offlineModeConnector.getOfflineModeTimeout() > -1) {
                    // This parameter is configurable from server via connector
                    pingTimeout = offlineModeConnector.getOfflineModeTimeout();
                }

                if (networkOnline && !forcedOffline) {
                    applicationConnection.getHeartbeat().setInterval(pingTimeout / 1000);
                } else {
                    applicationConnection.getHeartbeat().setInterval(-1);
                }
            } else {
                if (networkOnline && !forcedOffline) {
                    pingToServer.schedule(pingTimeout);
                } else {
                    pingToServer.cancel();
                }
            }
        }
    }

    /**
     * @return the OfflineMode application.
     */
    public static OfflineMode getOfflineMode() {
        return offlineModeApp;
    }

    /*
     * Go online if we were not, deactivating off-line UI
     * and reactivating the online one.
     */
    private void goOnline(ActivationReason reason) {
        if (!online && networkOnline && serverAvailable && !forcedOffline) {
            lastReason = reason;
            logger.info("Network Back ONLINE (" + reason + ")");
            online = true;
            if (offlineModeConnector != null) {
                if (offlineModeApp.isActive()) {
                    offlineModeApp.deactivate();
                }
                applicationConnection.setApplicationRunning(true);
                applicationConnection.fireEvent(new OnlineEvent());
            } else {
                // Notify offline UI that the user has to reload the app.
                lastReason = ONLINE_APP_NOT_STARTED;
                offlineModeApp.activate(lastReason);
            }
            configureHeartBeat();
        }
    }

    /*
     * Go off-line showing the off-line UI, or notify it
     * with the last off-line reason.
     */
    private void goOffline(ActivationReason reason) {
        logger.info("Network OFFLINE (" + reason + ")");
        online = false;
        lastReason = reason;
        offlineModeApp.activate(reason);
        if (offlineModeConnector != null) {
            applicationConnection.setApplicationRunning(false);
            applicationConnection.fireEvent(new OfflineEvent(reason));
        }
        configureHeartBeat();
    }

    @Override
    public void onResponseReceived(Request request, Response response) {
        if (response != null && response.getStatusCode() == Response.SC_OK) {
            dispatch(SERVER_AVAILABLE);
        } else {
            dispatch(RESPONSE_TIMEOUT);
        }
    }

    @Override
    public void onError(Request request, Throwable exception) {
        dispatch(BAD_RESPONSE);
    }

    @Override
    public boolean onError(String details, int statusCode) {
        dispatch(BAD_RESPONSE);
        return true;
    }

    @Override
    public void onRequestStarting(RequestStartingEvent e) {
    }

    @Override
    public void onResponseHandlingStarted(ResponseHandlingStartedEvent e) {
        dispatch(SERVER_AVAILABLE);
    }

    @Override
    public void onResponseHandlingEnded(ResponseHandlingEndedEvent e) {
    }

    @Override
    public void onConnectionStatusChange(ConnectionStatusEvent event) {
        if (event.getStatus() == Response.SC_OK ) {
            dispatch(SERVER_AVAILABLE);
        } else {
            dispatch(BAD_RESPONSE);
        }
    }

    /**
     * Check whether the server is reachable setting the status on the response.
     */
    public void ping() {
        logger.fine("Sending ping to server.");
        if (applicationConnection != null) {
            applicationConnection.getHeartbeat().send();
        } else {
            pingToServer.run();
        }
    }

    /*
     * Using this JSNI block in order to listen to certain DOM events not available
     * in GWT: HTML-5 and Cordova online/offline.
     *
     * We also listen to hash fragment changes and window post-messages, so as the app
     * is notified with offline events from the parent when it is embedded in an iframe.
     *
     * This block has a couple of hacks to make the app or network go off-line:
     *   tkGoOffline() tkGoOnline() tkServerDown() tkServerUp()
     *
     * NOTE: Most code here is for fixing android bugs firing wrong events and setting
     * erroneously online flags when it is inside webview.
     */
    private native void configureApplicationOfflineEvents() /*-{
        var _this = this;
        var hasCordovaEvents = false;

        function offline() {
          console.log(">>> Network flag is offline.");
          var ev = @com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason::NO_NETWORK;
          _this.@com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::dispatch(*)(ev);
        }
        function online() {
          console.log(">>> Network flag is online.");
          var ev = @com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason::NETWORK_ONLINE;
          _this.@com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::dispatch(*)(ev);
        }

        // Export some functions for allowing developer to switch network and server on/off from JS console
        var forceFailure = false;
        $wnd.tkServerDown = function() {
          forceFailure = true;
        }
        $wnd.tkServerUp = function() {
          forceFailure = false;
        }
        $wnd.tkGoOffline = function() {
          var ev = @com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason::FORCE_OFFLINE;
          _this.@com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::dispatch(*)(ev);
        }
        $wnd.tkGoOnline = function() {
          var ev = @com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineMode.ActivationReason::FORCE_ONLINE;
          _this.@com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::dispatch(*)(ev);
        }
        // When offline is forced make any XHR fail
        var realSend = $wnd.XMLHttpRequest.prototype.send;
        $wnd.XMLHttpRequest.prototype.send = function() {
          if (forceFailure || _this.@com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::forcedOffline) {
            throw "NETWORK_FAILURE_FORCED";
          } else {
            realSend.apply(this, arguments);
          }
        }

        // Listen to HTML5 offline-online events
        if ($wnd.navigator.onLine != undefined) {
          $wnd.addEventListener("offline", function() {
            if (!hasCordovaEvents) offline();
          }, false);
          $wnd.addEventListener("online", function() {
            if (!hasCordovaEvents) online();
          }, false);
          // use HTML5 to test whether connection is available when the app starts
          if (!$wnd.navigator.onLine) {
            offline();
          }
        }

        // Redefine the HTML-5 onLine indicator.
        // This fixes the issue of android inside phonegap returning erroneus values.
        // It allows old vaadin apps based on testing 'onLine' flag continuing working.
        Object.defineProperty($wnd.navigator, 'onLine', {
          set: function() {},
          get: function() {
            return @com.vaadin.addon.touchkit.gwt.client.offlinemode.OfflineModeEntrypoint::online;
          }
        });

        // Listen to Cordova specific online/off-line stuff
        // this needs cordova.js to be loaded in the current page.
        // It has to be done overriding ApplicationCacheSettings.
        if ($wnd.navigator.network && $wnd.navigator.network.connection && $wnd.Connection) {
          hasCordovaEvents = true;
          $doc.addEventListener("offline", offline, false);
          $doc.addEventListener("online", online, false);
          // use Cordova to test whether connection is available when the app starts
          if ($wnd.navigator.network.connection.type == $wnd.Connection.NONE) {
            offline();
          }
        }

        // Use postMessage approach to go online-offline, useful when the
        // application is embedded in a Cordova iframe, so as it
        // can pass network status messages to the iframe.
        if ($wnd.postMessage) {
          $wnd.addEventListener("message", function(ev) {
            var msg = ev.data;
            console.log(">>> received window message " + msg);
            if (/^(cordova-.+)$/.test(msg)) {
              hasCordovaEvents = true;
              // Take an action depending on the message
              if (msg == 'cordova-offline') {
                offline();
              } else if (msg == 'cordova-online') {
                online();
              } // TODO: handle pause & resume messages
            }
          }, false);
          // Notify parent cordova container about the app was loaded.
          $wnd.parent.window.postMessage("touchkit-ready", "*");
        }
    }-*/;
}
