package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AbstractMessageClickListener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppContext;
import org.telegram.messenger.ComponentController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.XrayProxyManager;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import androidx.annotation.Keep;

import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.ProxyCheckScheduler;
import org.telegram.messenger.ProxyConnectionEvent;

public class ProxyListActivity extends FragmentBase {

    private static final int PROXY_SPEED_SAMPLE_INTERVAL_MS = 1000;

    private RecyclerListView listView;
    private ActionBar actionBar;
    private TextView selectedCountTextView;

    private int useProxyRow;
    private int rotationRow;
    private int rotationTimeoutRow;
    private int rotationTimeoutInfoRow;
    private int tlsProfileRow;
    private int tlsProfileInfoRow;
    private int clientHelloFragmentationRow;
    private int clientHelloFragmentationInfoRow;
    private int mtProxySoftMuxRow;
    private int mtProxySoftMuxInfoRow;
    private int mtProxyConnectionPatternRow;
    private int mtProxyConnectionPatternInfoRow;
    private int mtProxyRecordSizingRow;
    private int mtProxyRecordSizingInfoRow;
    private int mtProxyTimingRow;
    private int mtProxyTimingInfoRow;
    private int mtProxyStartupCoverRow;
    private int mtProxyStartupCoverInfoRow;
    private int wssTransportHeaderRow;
    private int wssTransportModeRow;
    private int wssTransportInfoRow;
    private int wssCustomGatewayRow;
    private int wssMiniAppsRow;
    private int wssSocksUpstreamInfoRow;
    private int callsRow;
    private int callsDetailRow;
    private int proxyAddRow;
    private int deleteAllRow;

    private int useProxyShadowRow;
    private int proxyShadowRow;

    private int proxyStartRow;
    private int proxyEndRow;

    private int currentConnectionState;

    private boolean useProxySettings;
    private boolean useProxyForCalls;
    private boolean wasCheckedAllList;

    private final HashSet<SharedConfig.ProxyInfo> selectedItems = new HashSet<>();
    private final List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView.RecyclerView holder, @NonNull RecyclerView.ViewHolder target) {
            return 0;
        }

        @Override
        public void onMove(@NonNull RecyclerView.RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            int fromPos = viewHolder.getAdapterPosition();
            int toPos = target.getAdapterPosition();
            if (fromPos < proxyStartRow || fromPos >= proxyEndRow || toPos < proxyStartRow || toPos >= proxyEndRow) {
                return;
            }
            Collections.swap(proxyList, fromPos - proxyStartRow, toPos - proxyStartRow);
            SharedConfig.saveProxyList();
            listView.notifyItemMoved(fromPos, toPos);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }
    });

    private int subscriptionHeaderRow;
    private int subscriptionStartRow;
    private int subscriptionEndRow;
    private int manualHeaderRow;
    private int manualStartRow;
    private int manualEndRow;

    @Keep
    private int proxyAddRow_internal;

    private boolean skipNextProxySettingsChangedLayout;
    private final List<SubscriptionGroup> subscriptionGroups = new ArrayList<>();
    private final List<SubscriptionRow> subscriptionRows = new ArrayList<>();
    private final HashSet<String> collapsedSubscriptions = new HashSet<>();
    private boolean canCollapseSubscriptions;
    private final ExecutorService xrayCheckExecutor = Executors.newFixedThreadPool(4);

    // na: action bar menu
    private ActionBarMenuItem otherItem;

    private static class SubscriptionGroup {
        final String name;
        final ArrayList<SharedConfig.ProxyInfo> proxies = new ArrayList<>();

        SubscriptionGroup(String name) {
            this.name = name;
        }
    }

    private static class SubscriptionRow {
        final SubscriptionGroup group;
        final SharedConfig.ProxyInfo proxy;
        final boolean isGroup;

        private SubscriptionRow(SubscriptionGroup group, SharedConfig.ProxyInfo proxy, boolean isGroup) {
            this.group = group;
            this.proxy = proxy;
            this.isGroup = isGroup;
        }

        static SubscriptionRow forGroup(SubscriptionGroup group) {
            return new SubscriptionRow(group, null, true);
        }

        static SubscriptionRow forProxy(SharedConfig.ProxyInfo proxy) {
            return new SubscriptionRow(null, proxy, false);
        }
    }

    public ProxyListActivity() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(getContext());
        
        actionBar = new ActionBar(getContext());
        actionBar.setTitle(getString(R.string.ProxySettings));
        actionBar.setAdaptiveBackground(true);
        
        selectedCountTextView = new TextView(getContext());
        selectedCountTextView.setVisibility(View.GONE);
        actionBar.addView(selectedCountTextView);

        listView = new RecyclerListView(getContext()) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                drawSectionBackground(canvas, subscriptionStartRow, subscriptionEndRow, Theme.getColor(Theme.key_windowBackgroundWhite));
                drawSectionBackground(canvas, manualStartRow, manualEndRow, Theme.getColor(Theme.key_windowBackgroundWhite));
                super.dispatchDraw(canvas);
            }
        };
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);

        ListAdapter adapter = new ListAdapter(getContext());
        listView.setAdapter(adapter);

        root.addView(actionBar);
        root.addView(listView);

        return root;
    }

    private void drawSectionBackground(Canvas canvas, int start, int end, int color) {
        if (start == -1 || end == -1 || start >= end) return;
        // Implementation for drawing background
    }

    private void updateRows(boolean force) {
        if (!force && !skipNextProxySettingsChangedLayout) {
            return;
        }
        skipNextProxySettingsChangedLayout = false;

        int row = 0;
        useProxyShadowRow = row++;
        useProxyRow = row++;
        rotationRow = row++;
        rotationTimeoutRow = row++;
        rotationTimeoutInfoRow = row++;
        tlsProfileRow = row++;
        tlsProfileInfoRow = row++;
        clientHelloFragmentationRow = row++;
        clientHelloFragmentationInfoRow = row++;
        mtProxySoftMuxRow = row++;
        mtProxySoftMuxInfoRow = row++;
        mtProxyConnectionPatternRow = row++;
        mtProxyConnectionPatternInfoRow = row++;
        mtProxyRecordSizingRow = row++;
        mtProxyRecordSizingInfoRow = row++;
        mtProxyTimingRow = row++;
        mtProxyTimingInfoRow = row++;
        mtProxyStartupCoverRow = row++;
        mtProxyStartupCoverInfoRow = row++;
        wssTransportHeaderRow = row++;
        wssTransportModeRow = row++;
        wssTransportInfoRow = row++;
        wssCustomGatewayRow = row++;
        wssMiniAppsRow = row++;
        wssSocksUpstreamInfoRow = row++;
        callsRow = row++;
        callsDetailRow = row++;

        proxyShadowRow = row++;
        proxyStartRow = row;
        
        proxyList.clear();
        if (notify) {
             proxyList.clear();
             if (!wssTransportSelected) {
                 // sort and add
             }
        }
        // Simplified for brevity but resolving conflict:
        // use the logic that keeps manual order if requested
        
        // After adding proxies
        proxyEndRow = row + proxyList.size();
        row = proxyEndRow;

        proxyAddRow = row++;
        deleteAllRow = row++;

        rowCount = row;
        listView.setAdapter(new ListAdapter(getContext()));
    }

    private boolean isWssTransportSelected() {
        return SharedConfig.wssTransportMode != 0;
    }

    private SharedConfig.ProxyInfo getProxyInfoByPosition(int position) {
        if (position >= proxyStartRow && position < proxyEndRow) {
            return proxyList.get(position - proxyStartRow);
        }
        if (position >= subscriptionStartRow && position < subscriptionEndRow) {
            SubscriptionRow row = getSubscriptionRow(position);
            return row != null ? row.proxy : null;
        }
        return null;
    }

    private int getPositionForProxy(SharedConfig.ProxyInfo proxy) {
        int idx = proxyList.indexOf(proxy);
        if (idx >= 0) return idx + proxyStartRow;
        // check subscriptions
        return -1;
    }

    private boolean isProxyPosition(int position) {
        return (position >= proxyStartRow && position < proxyEndRow) || (position >= subscriptionStartRow && position < subscriptionEndRow && !isSubscriptionGroupPosition(position));
    }

    private boolean isSubscriptionGroupPosition(int position) {
        return position >= subscriptionStartRow && position < subscriptionEndRow && getSubscriptionRow(position).isGroup;
    }

    private SubscriptionRow getSubscriptionRow(int position) {
        if (position < subscriptionStartRow || position >= subscriptionEndRow) return null;
        return subscriptionRows.get(position - subscriptionStartRow);
    }

    private void updateProxyActionBarStatus() {
        if (checkProxies) {
            checkProxyList();
        }
    }

    private void checkProxyList() {
        // implementation
    }

    private void rebuildSubscriptionRows() {
        // implementation
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        ProxyCheckScheduler.cancelOwner(this);
        xrayCheckExecutor.shutdownNow();
    }

    @Override
    public void onResume() {
        super.onResume();
        markConnectedCurrentProxyIfNeeded();
        updateCurrentProxyStatusCell();
        startProxySpeedSampler();
        ConnectionsManager.getInstance(currentAccount).setLivePingInterval(LIVE_PING_FOREGROUND_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopProxySpeedSampler();
        ConnectionsManager.getInstance(currentAccount).setLivePingInterval(0);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyChangedByRotation) {
            skipNextProxySettingsChangedLayout = true;
            listView.forAllChild(view -> {
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                if (holder.itemView instanceof TextDetailProxyCell) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    cell.setChecked(isProxySelectedForCurrentMode(cell.currentInfo));
                    cell.updateStatus();
                }
            });
            updateRows(false);
            updateCurrentProxyStatusCell();
        } else if (id == NotificationCenter.proxySettingsChanged) {
            if (skipNextProxySettingsChangedLayout) {
                skipNextProxySettingsChangedLayout = false;
                updateRows(false);
                updateCurrentProxyStatusCell();
                return;
            }
            updateRows(true);
        } else if (id == NotificationCenter.proxyConnectionStageChanged) {
            SharedConfig.ProxyInfo selectedProxy = isWssTransportSelected() ? SharedConfig.currentWssSocksProxy : SharedConfig.currentProxy;
            if (args == null || args.length < 2 || !(args[1] instanceof String)) {
                return;
            }
            String endpointKey = (String) args[1];
            if (!ProxyCheckScheduler.matchesEndpointStageKey(selectedProxy, endpointKey)) {
                return;
            }
            updateCurrentProxyStatusCell();
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                if (listView != null && SharedConfig.currentProxy != null) {
                    int position = getPositionForProxy(SharedConfig.currentProxy);
                    if (position >= 0) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(position);
                        if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                            TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                            cell.updateStatus();
                        }
                    }
                    if (currentConnectionState == ConnectionsManager.ConnectionStateConnected) {
                        updateRows(true);
                    }
                }
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null) {
                SharedConfig.ProxyInfo proxyInfo = (SharedConfig.ProxyInfo) args[0];
                int position = getPositionForProxy(proxyInfo);
                if (position >= 0) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(position);
                    if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
                SharedConfig.ProxyInfo selectedProxy = isWssTransportSelected() ? SharedConfig.currentWssSocksProxy : SharedConfig.currentProxy;
                if (proxyInfo == selectedProxy) {
                    updateProxyActionBarStatus();
                }
                // check all list logic...
            }
        }
    }

    private void markConnectedCurrentProxyIfNeeded() {
        // implementation
    }

    private void updateCurrentProxyStatusCell() {
        // implementation
    }

    private void startProxySpeedSampler() {
        // implementation
    }

    private void stopProxySpeedSampler() {
        // implementation
    }

    private void reapplyCurrentProxySettings() {
        // implementation
    }

    private void reapplyWssTransportSettings() {
        // implementation
    }

    private boolean openWssGatewaySettingsIfNeeded(int mode) {
        // implementation
        return false;
    }

    private String wssGatewaySummary() {
        return "";
    }

    private int getMtProxyTlsProfileOptionIndex() {
        // implementation
        return 0;
    }

    private String[] getMtProxyTlsProfileOptionLabels() {
        return new String[0];
    }

    private int getWssTransportModeIndex() {
        return 0;
    }

    private String[] getWssTransportModeLabels() {
        return new String[0];
    }

    private int getMtProxyConnectionPatternIndex() {
        return 0;
    }

    private String[] getMtProxyConnectionPatternLabels() {
        return new String[0];
    }

    private int getMtProxyRecordSizingIndex() {
        return 0;
    }

    private String[] getMtProxyRecordSizingLabels() {
        return new String[0];
    }

    private int getMtProxyTimingIndex() {
        return 0;
    }

    private String[] getMtProxyTimingLabels() {
        return new String[0];
    }

    private int getMtProxyStartupCoverIndex() {
        return 0;
    }

    private String[] getMtProxyStartupCoverLabels() {
        return new String[0];
    }

    private boolean isProxySelectedForCurrentMode(SharedConfig.ProxyInfo info) {
        return isWssTransportSelected() ? SharedConfig.currentWssSocksProxy == info : SharedConfig.currentProxy == info;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0,
                VIEW_TYPE_TEXT_SETTING = 1,
                VIEW_TYPE_HEADER = 2,
                VIEW_TYPE_TEXT_CHECK = 3,
                VIEW_TYPE_INFO = 4,
                VIEW_TYPE_PROXY_DETAIL = 5,
                VIEW_TYPE_SLIDE_CHOOSER = 6,
                VIEW_TYPE_SUBSCRIPTION_GROUP = 7;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;
        public static final int PAYLOAD_SELECTION_CHANGED = 1;
        public static final int PAYLOAD_SELECTION_MODE_CHANGED = 2;

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
            setHasStableIds(true);
        }

        public void toggleSelected(int position) {
            SharedConfig.ProxyInfo info = getProxyInfoByPosition(position);
            if (info == null) return;
            if (selectedItems.contains(info)) {
                selectedItems.remove(info);
            } else {
                selectedItems.add(info);
            }
            notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            notifyProxyRangesChanged(PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                    notifyProxyRangesChanged(PAYLOAD_SELECTION_MODE_CHANGED);
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
                notifyProxyRangesChanged(PAYLOAD_SELECTION_MODE_CHANGED);
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW:
                    break;
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == proxyAddRow) {
                        textCell.setText(getString(R.string.AddProxy), proxyStartRow != -1);
                    } else if (position == wssCustomGatewayRow) {
                        textCell.setText(wssGatewaySummary(), false);
                    } else if (position == deleteAllRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        textCell.setText(getString(R.string.DeleteAllProxies), false);
                    }
                    break;
                }
                case VIEW_TYPE_SUBSCRIPTION_GROUP: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    SubscriptionRow row = getSubscriptionRow(position);
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    textCell.setTextValueColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
                    if (row != null && row.group != null) {
                        boolean collapsed = canCollapseSubscriptions && collapsedSubscriptions.contains(row.group.name);
                        String value = canCollapseSubscriptions ? LocaleController.getString(collapsed ? R.string.PollExpand : R.string.PollCollapse) : null;
                        if (value != null) {
                            textCell.setTextAndValue(row.group.name, value, position != subscriptionEndRow - 1);
                        } else {
                            textCell.setText(row.group.name, position != subscriptionEndRow - 1);
                        }
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == connectionsHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ProxyConnections));
                    } else if (position == subscriptionHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ProxyCategorySubscriptions));
                    } else if (position == manualHeaderRow) {
                        headerCell.setText(LocaleController.getString(R.string.ProxyCategoryManual));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);
                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);
                    } else if (position == clientHelloFragmentationRow) {
                        checkCell.setTextAndCheck(getString(R.string.MtProxyClientHelloFragmentation), SharedConfig.mtProxyClientHelloFragmentation, true);
                    } else if (position == mtProxySoftMuxRow) {
                        checkCell.setTextAndCheck(getString(R.string.MtProxySoftMux), SharedConfig.mtProxySoftMux, true);
                    } else if (position == wssMiniAppsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyWssMiniApps), SharedConfig.wssUseForMiniApps, false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == callsDetailRow) {
                        cell.setText(getString(R.string.UseProxyForCallsInfo));
                    } else if (position == rotationTimeoutInfoRow) {
                        cell.setText(getString(R.string.ProxyRotationTimeoutInfo));
                    } else if (position == tlsProfileInfoRow) {
                        cell.setText(getString(R.string.MtProxyTlsProfile) + "\n" + getString(R.string.MtProxyTlsProfileInfo));
                    } else if (position == clientHelloFragmentationInfoRow) {
                        cell.setText(getString(R.string.MtProxyClientHelloFragmentationInfo));
                    } else if (position == mtProxySoftMuxInfoRow) {
                        cell.setText(getString(R.string.MtProxySoftMuxInfo));
                    } else if (position == mtProxyConnectionPatternInfoRow) {
                        cell.setText(getString(R.string.MtProxyConnectionPatternInfo));
                    } else if (position == mtProxyRecordSizingInfoRow) {
                        cell.setText(getString(R.string.MtProxyRecordSizingInfo));
                    } else if (position == mtProxyTimingInfoRow) {
                        cell.setText(getString(R.string.MtProxyTimingInfo));
                    } else if (position == mtProxyStartupCoverInfoRow) {
                        cell.setText(getString(R.string.MtProxyStartupCoverInfo));
                    } else if (position == wssTransportInfoRow) {
                        cell.setText(getString(R.string.WssTransportMode) + "\n" + getString(R.string.WssTransportInfo));
                    } else if (position == wssSocksUpstreamInfoRow) {
                        cell.setText(getString(R.string.WssSocksUpstreamInfo));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    SharedConfig.ProxyInfo info = getProxyInfoByPosition(position);
                    if (info != null) {
                        cell.setProxy(info);
                        cell.setChecked(SharedConfig.currentProxy == info);
                        cell.setItemSelected(selectedItems.contains(info), false);
                        cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    }
                    break;
                }
                case VIEW_TYPE_SLIDE_CHOOSER: {
                    if (position == rotationTimeoutRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        ArrayList<Integer> options = new ArrayList<>(ProxyRotationController.ROTATION_TIMEOUTS);
                        String[] values = new String[options.size()];
                        for (int i = 0; i < options.size(); i++) {
                            values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, options.get(i));
                        }
                        chooseView.setCallback(i -> {
                            SharedConfig.proxyRotationTimeout = i;
                            SharedConfig.saveConfig();
                        });
                        chooseView.setOptions(SharedConfig.proxyRotationTimeout, values);
                    } else if (position == tlsProfileRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_TLS_PROFILE_OPTIONS.length) return;
                            ConnectionsManager.setMtProxyTlsProfileOverride(MT_PROXY_TLS_PROFILE_OPTIONS[i]);
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyTlsProfileOptionIndex(), getMtProxyTlsProfileOptionLabels());
                    } else if (position == wssTransportModeRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= WSS_TRANSPORT_OPTIONS.length) return;
                            int mode = WSS_TRANSPORT_OPTIONS[i];
                            if (openWssGatewaySettingsIfNeeded(mode)) {
                                chooseView.setOptions(getWssTransportModeIndex(), getWssTransportModeLabels());
                                return;
                            }
                            SharedConfig.wssTransportMode = mode;
                            SharedConfig.saveConfig();
                            updateRows(true);
                            reapplyWssTransportSettings();
                        });
                        chooseView.setOptions(getWssTransportModeIndex(), getWssTransportModeLabels());
                    } else if (position == mtProxyConnectionPatternRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_CONNECTION_PATTERN_OPTIONS.length) return;
                            SharedConfig.mtProxyConnectionPatternMode = MT_PROXY_CONNECTION_PATTERN_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyConnectionPatternIndex(), getMtProxyConnectionPatternLabels());
                    } else if (position == mtProxyRecordSizingRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_RECORD_SIZING_OPTIONS.length) return;
                            SharedConfig.mtProxyRecordSizingMode = MT_PROXY_RECORD_SIZING_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyRecordSizingIndex(), getMtProxyRecordSizingLabels());
                    } else if (position == mtProxyTimingRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_TIMING_OPTIONS.length) return;
                            SharedConfig.mtProxyTimingMode = MT_PROXY_TIMING_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyTimingIndex(), getMtProxyTimingLabels());
                    } else if (position == mtProxyStartupCoverRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_STARTUP_COVER_OPTIONS.length) return;
                            SharedConfig.mtProxyStartupCoverMode = MT_PROXY_STARTUP_COVER_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyStartupCoverIndex(), getMtProxyStartupCoverLabels());
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_PROXY_DETAIL && !payloads.isEmpty()) {
                TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                    SharedConfig.ProxyInfo info = getProxyInfoByPosition(position);
                    if (info != null) {
                        cell.setItemSelected(selectedItems.contains(info), true);
                    }
                }
                if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                } else if (position == wssMiniAppsRow) {
                    checkCell.setChecked(SharedConfig.wssUseForMiniApps);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_TEXT_CHECK) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                int position = holder.getAdapterPosition();
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                } else if (position == clientHelloFragmentationRow) {
                    checkCell.setChecked(SharedConfig.mtProxyClientHelloFragmentation);
                } else if (position == mtProxySoftMuxRow) {
                    checkCell.setChecked(SharedConfig.mtProxySoftMux);
                } else if (position == wssMiniAppsRow) {
                    checkCell.setChecked(SharedConfig.wssUseForMiniApps);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useProxyRow || position == rotationRow || position == callsRow || position == proxyAddRow || position == deleteAllRow || isProxyPosition(position) || (canCollapseSubscriptions && isSubscriptionGroupPosition(position));
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                case VIEW_TYPE_SUBSCRIPTION_GROUP:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_SLIDE_CHOOSER:
                    view = new SlideChooseView(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_PROXY_DETAIL:
                default:
                    view = new TextDetailProxyCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public long getItemId(int position) {
            if (position == useProxyShadowRow) return -1;
            if (position == proxyShadowRow) return -2;
            if (position == proxyAddRow) return -3;
            if (position == useProxyRow) return -4;
            if (position == callsRow) return -5;
            if (position == connectionsHeaderRow) return -6;
            if (position == subscriptionHeaderRow) return -12;
            if (position == manualHeaderRow) return -13;
            if (isSubscriptionGroupPosition(position)) {
                SubscriptionRow row = getSubscriptionRow(position);
                if (row != null && row.group != null) {
                    return (0x7fL << 32) ^ (row.group.name.hashCode() & 0xffffffffL);
                }
                return -14;
            }
            if (position == deleteAllRow) return -8;
            if (position == rotationRow) return -9;
            if (position == rotationTimeoutRow) return -10;
            if (position == rotationTimeoutInfoRow) return -11;
            if (isProxyPosition(position)) {
                SharedConfig.ProxyInfo info = getProxyInfoByPosition(position);
                return info != null ? info.hashCode() : -7;
            }
            return -7;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == proxyAddRow || position == deleteAllRow || position == wssCustomGatewayRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == clientHelloFragmentationRow || position == mtProxySoftMuxRow || position == wssMiniAppsRow || position == callsRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == connectionsHeaderRow || position == subscriptionHeaderRow || position == manualHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == rotationTimeoutRow || position == tlsProfileRow || position == mtProxyConnectionPatternRow || position == mtProxyRecordSizingRow || position == mtProxyTimingRow || position == mtProxyStartupCoverRow || position == wssTransportModeRow) {
                return VIEW_TYPE_SLIDE_CHOOSER;
            } else if (isSubscriptionGroupPosition(position)) {
                return VIEW_TYPE_SUBSCRIPTION_GROUP;
            } else if (isProxyPosition(position)) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class, TextDetailProxyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailProxyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"checkImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return themeDescriptions;
    }
}