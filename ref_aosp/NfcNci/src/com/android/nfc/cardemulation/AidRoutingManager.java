/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.nfc.cardemulation;

import android.annotation.IntDef;
import android.nfc.NfcOemExtension;
import android.sysprop.NfcProperties;
import android.util.Log;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.VisibleForTesting;

import com.android.nfc.NfcService;
import com.android.nfc.NfcStatsLog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AidRoutingManager {

    static final String TAG = "AidRoutingManager";

    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);

    static final int ROUTE_HOST = 0x00;

    // Every routing table entry is matched exact
    static final int AID_MATCHING_EXACT_ONLY = 0x00;
    // Every routing table entry can be matched either exact or prefix
    static final int AID_MATCHING_EXACT_OR_PREFIX = 0x01;
    // Every routing table entry is matched as a prefix
    static final int AID_MATCHING_PREFIX_ONLY = 0x02;
    // Every routing table entry can be matched either exact or prefix or subset only
    static final int AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX = 0x03;

    int mDefaultIsoDepRoute;
    //Let mDefaultRoute as default aid route
    int mDefaultRoute;
    int mPowerEmptyAid = 0x00;

    int mMaxAidRoutingTableSize;

    final byte[] mOffHostRouteUicc;
    final byte[] mOffHostRouteEse;
    // Used for backward compatibility in case application doesn't specify the
    // SE
    int mDefaultOffHostRoute;

    int mDefaultFelicaRoute;

    // How the NFC controller can match AIDs in the routing table;
    // see AID_MATCHING constants
    final int mAidMatchingSupport;

    final Object mLock = new Object();

    // mAidRoutingTable contains the current routing table. The index is the route ID.
    // The route can include routes to a eSE/UICC.
    SparseArray<Set<String>> mAidRoutingTable =
            new SparseArray<Set<String>>();

    // Easy look-up what the route is for a certain AID
    HashMap<String, Integer> mRouteForAid = new HashMap<String, Integer>();
    // Easy look-up what the power is for a certain AID
    HashMap<String, Integer> mPowerForAid = new HashMap<String, Integer>();

    RoutingOptionManager mRoutingOptionManager = RoutingOptionManager.getInstance();
    @VisibleForTesting
    public final class AidEntry {
        boolean isOnHost;
        String offHostSE;
        int route;
        int aidInfo;
        int power;
        List<String> unCheckedOffHostSE = new ArrayList<>();
    }

    public AidRoutingManager() {
        mDefaultRoute = mRoutingOptionManager.getDefaultRoute();
        if (DBG) Log.d(TAG, "mDefaultRoute=0x" + Integer.toHexString(mDefaultRoute));
        mDefaultOffHostRoute = mRoutingOptionManager.getDefaultOffHostRoute();
        if (DBG) Log.d(TAG, "mDefaultOffHostRoute=0x" + Integer.toHexString(mDefaultOffHostRoute));
        mDefaultFelicaRoute = mRoutingOptionManager.getDefaultFelicaRoute();
        if (DBG) Log.d(TAG, "mDefaultFelicaRoute=0x" + Integer.toHexString(mDefaultFelicaRoute));
        mOffHostRouteUicc = mRoutingOptionManager.getOffHostRouteUicc();
        if (DBG) Log.d(TAG, "mOffHostRouteUicc=" + Arrays.toString(mOffHostRouteUicc));
        mOffHostRouteEse = mRoutingOptionManager.getOffHostRouteEse();
        if (DBG) Log.d(TAG, "mOffHostRouteEse=" + Arrays.toString(mOffHostRouteEse));
        mAidMatchingSupport = mRoutingOptionManager.getAidMatchingSupport();
        if (DBG) Log.d(TAG, "mAidMatchingSupport=0x" + Integer.toHexString(mAidMatchingSupport));
        mDefaultIsoDepRoute = mRoutingOptionManager.getDefaultIsoDepRoute();
        if (DBG) Log.d(TAG, "mDefaultIsoDepRoute=0x" + Integer.toHexString(mDefaultIsoDepRoute));
    }

    public boolean supportsAidPrefixRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX
                || mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY
                || mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    public boolean supportsAidSubsetRouting() {
        return mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX;
    }

    public int calculateAidRouteSize(HashMap<String, AidEntry> routeCache) {
        // TAG + ROUTE + LENGTH_BYTE + POWER
        int AID_HDR_LENGTH = 0x04;
        int routeTableSize = 0x00;
        for (Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet()) {
            String aid = aidEntry.getKey();
            // removing prefix length
            if (aid.endsWith("*")) {
                routeTableSize += ((aid.length() - 0x01) / 0x02) + AID_HDR_LENGTH;
            } else {
                routeTableSize += (aid.length() / 0x02)+ AID_HDR_LENGTH;
            }
        }
        if (DBG) Log.d(TAG, "calculateAidRouteSize: " + routeTableSize);
        return routeTableSize;
    }

    private void clearNfcRoutingTableLocked() {
        if (DBG) Log.d(TAG, "clearNfcRoutingTableLocked");
        NfcService.getInstance().clearRoutingTable(0x01);
    }

    //Checking in case of power/route update of any AID after conflict
    //resolution, is routing required or not?
    private boolean isAidEntryUpdated(HashMap<String, Integer> currRouteForAid,
                                                Map.Entry<String, Integer> aidEntry,
                                                HashMap<String, Integer> prevPowerForAid) {
        if (!Objects.equals(currRouteForAid.get(aidEntry.getKey()), aidEntry.getValue())
                || !Objects.equals(
                mPowerForAid.get(aidEntry.getKey()),
                prevPowerForAid.get(aidEntry.getKey()))) {
            return true;
        }
        return false;
    }

    //Check if Any AID entry needs to be removed from previously registered
    //entries in the Routing table. Current AID entries & power state are part of
    //mRouteForAid & mPowerForAid respectively. previously registered AID entries &
    //power states are part of input argument prevRouteForAid & prevPowerForAid respectively.
    private boolean checkUnrouteAid(HashMap<String, Integer> prevRouteForAid,
                                     HashMap<String, Integer> prevPowerForAid) {
        for (Map.Entry<String, Integer> aidEntry : prevRouteForAid.entrySet())  {
            if ((aidEntry.getValue() != mDefaultRoute)
                    && (!mRouteForAid.containsKey(aidEntry.getKey())
                    || isAidEntryUpdated(mRouteForAid, aidEntry, prevPowerForAid))) {
                return true;
            }
        }
        return false;
    }

    //Check if Any AID entry needs to be added to previously registered
    //entries in the Routing table. Current AID entries & power state are part of
    //mRouteForAid & mPowerForAid respectively. previously registered AID entries &
    //power states are part of input argument prevRouteForAid & prevPowerForAid respectively.
    private boolean checkRouteAid(HashMap<String, Integer> prevRouteForAid,
                                   HashMap<String, Integer> prevPowerForAid) {
        for (Map.Entry<String, Integer> aidEntry : mRouteForAid.entrySet())  {
            if ((aidEntry.getValue() != mDefaultRoute)
                    && (!prevRouteForAid.containsKey(aidEntry.getKey())
                    || isAidEntryUpdated(prevRouteForAid, aidEntry, prevPowerForAid))) {
                return true;
            }
        }
        return false;
    }

    private boolean checkRoutingOptionChanged(int prevDefaultRoute, int prevDefaultIsoDepRoute,
                                              int prevDefaultOffHostRoute) {
        return (prevDefaultRoute != mDefaultRoute)
                || (prevDefaultIsoDepRoute != mDefaultIsoDepRoute)
                || (prevDefaultOffHostRoute != mDefaultOffHostRoute);
    }

    private void checkOffHostRouteToHost(HashMap<String, AidEntry> routeCache) {
        Iterator<Map.Entry<String, AidEntry> > it = routeCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AidEntry> entry = it.next();
            String aid = entry.getKey();
            AidEntry aidEntry = entry.getValue();

            if (!aidEntry.isOnHost || aidEntry.unCheckedOffHostSE.size() == 0) {
                continue;
            }
            boolean mustHostRoute = aidEntry.unCheckedOffHostSE.stream()
                    .anyMatch(offHost ->mRoutingOptionManager.getRouteForSecureElement(offHost)
                            == mDefaultRoute);
            if (mustHostRoute) {
                if (DBG) {
                    Log.d(TAG,
                            "checkOffHostRouteToHost: " + aid
                                    + " is route to host due to unchecked off host and "
                                    + "default route(0x" + Integer.toHexString(mDefaultRoute)
                                    + ") is same");
                }
            } else {
                if (DBG) {
                    Log.d(TAG, "checkOffHostRouteToHost: " + aid + " remove in host route list");
                }
                it.remove();
            }
        }
    }

    public static final int CONFIGURE_ROUTING_SUCCESS = 0;
    public static final int CONFIGURE_ROUTING_FAILURE_TABLE_FULL = 1;
    public static final int CONFIGURE_ROUTING_FAILURE_UNKNOWN = 2;
    @IntDef(flag = true, value = {
        CONFIGURE_ROUTING_SUCCESS,
        CONFIGURE_ROUTING_FAILURE_TABLE_FULL,
        CONFIGURE_ROUTING_FAILURE_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConfigureRoutingResult {}

    /**
     * Configures the routing table with the given {@code aidMap}.
     *
     * @param aidMap The map of AIDs to their corresponding {@link AidEntry}.
     * @param force Whether to force the configuration even if the routing table is unchanged.
     * @param isOverrideOrRecover Whether the configuration is requested when override/recover
     *                            routing table.
     * @return The failure reason if the configuration failed.
     */
    @ConfigureRoutingResult
    public int configureRouting(HashMap<String, AidEntry> aidMap, boolean force,
            boolean isOverrideOrRecover) {
        boolean aidRouteResolved = false;
        HashMap<String, AidEntry> aidRoutingTableCache = new HashMap<String, AidEntry>(aidMap.size());
        ArrayList<Integer> seList = new ArrayList<Integer>();

        int prevDefaultRoute = mDefaultRoute;
        int prevDefaultIsoDepRoute = mDefaultIsoDepRoute;
        int prevDefaultOffHostRoute = mDefaultOffHostRoute;

        if (mRoutingOptionManager.isRoutingTableOverrided()) {
            mDefaultRoute = mRoutingOptionManager.getOverrideDefaultRoute();
            mDefaultIsoDepRoute = mRoutingOptionManager.getOverrideDefaultIsoDepRoute();
            mDefaultOffHostRoute = mRoutingOptionManager.getOverrideDefaultOffHostRoute();
            mDefaultFelicaRoute = mRoutingOptionManager.getOverrideDefaultFelicaRoute();
        } else {
            mDefaultRoute = mRoutingOptionManager.getDefaultRoute();
            mDefaultIsoDepRoute = mRoutingOptionManager.getDefaultIsoDepRoute();
            mDefaultOffHostRoute = mRoutingOptionManager.getDefaultOffHostRoute();
            mDefaultFelicaRoute = mRoutingOptionManager.getDefaultFelicaRoute();
        }

        boolean isPowerStateUpdated = false;
        seList.add(mDefaultRoute);
        if (mDefaultRoute != ROUTE_HOST) {
            seList.add(ROUTE_HOST);
        }

        SparseArray<Set<String>> aidRoutingTable = new SparseArray<Set<String>>(aidMap.size());
        HashMap<String, Integer> routeForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> powerForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> infoForAid = new HashMap<String, Integer>(aidMap.size());
        HashMap<String, Integer> prevRouteForAid = new HashMap<String, Integer>();
        HashMap<String, Integer> prevPowerForAid = new HashMap<String, Integer>();
        // Then, populate internal data structures first
        for (Map.Entry<String, AidEntry> aidEntry : aidMap.entrySet())  {
            int route = ROUTE_HOST;
            if (!aidEntry.getValue().isOnHost) {
                String offHostSE = aidEntry.getValue().offHostSE;
                if (offHostSE == null) {
                    route = mDefaultOffHostRoute;
                } else {
                    route = mRoutingOptionManager.getRouteForSecureElement(offHostSE);
                    if (route == 0) {
                        Log.e(TAG, "configureRouting: Invalid Off host Aid Entry " + offHostSE);
                        continue;
                    }
                }
            }
            if (!seList.contains(route))
                seList.add(route);
            aidEntry.getValue().route = route;
            int aidType = aidEntry.getValue().aidInfo;
            int power = aidEntry.getValue().power;
            String aid = aidEntry.getKey();
            Set<String> entries =
                    aidRoutingTable.get(route, new HashSet<String>());
            entries.add(aid);
            aidRoutingTable.put(route, entries);
            routeForAid.put(aid, route);
            powerForAid.put(aid, power);
            infoForAid.put(aid, aidType);
        }

        if (!mRoutingOptionManager.isAutoChangeEnabled() && seList.size() >= 2) {
            Log.d(TAG, "configureRouting: AutoRouting is not enabled, make only one item in list");
            int firstRoute = seList.get(0);
            seList.clear();
            seList.add(firstRoute);
        }

        synchronized (mLock) {
            if (routeForAid.equals(mRouteForAid) && powerForAid.equals(mPowerForAid) && !force) {
                if (DBG) Log.d(TAG, "configureRouting: Routing table unchanged, not updating");
                return CONFIGURE_ROUTING_SUCCESS;
            }

            // Otherwise, update internal structures and commit new routing
            prevRouteForAid = mRouteForAid;
            mRouteForAid = routeForAid;
            prevPowerForAid = mPowerForAid;
            mPowerForAid = powerForAid;
            mAidRoutingTable = aidRoutingTable;

            mMaxAidRoutingTableSize = NfcService.getInstance().getAidRoutingTableSize();
            if (DBG) {
                Log.d(TAG, "configureRouting: mMaxAidRoutingTableSize: " + mMaxAidRoutingTableSize);
            }

            //calculate AidRoutingTableSize for existing route destination
            for (int index = 0; index < seList.size(); index++) {
                mDefaultRoute = seList.get(index);
                if (index != 0) {
                    if (DBG) {
                        Log.d(TAG, "configureRouting: AidRoutingTable is full, try to switch "
                                + "mDefaultRoute to 0x" + Integer.toHexString(mDefaultRoute));
                    }
                }

                aidRoutingTableCache.clear();

                if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                    /* If a non-default route registers an exact AID which is shorter
                     * than this exact AID, this will create a problem with controllers
                     * that treat every AID in the routing table as a prefix.
                     * For example, if App A registers F0000000041010 as an exact AID,
                     * and App B registers F000000004 as an exact AID, and App B is not
                     * the default route, the following would be added to the routing table:
                     * F000000004 -> non-default destination
                     * However, because in this mode, the controller treats every routing table
                     * entry as a prefix, it means F0000000041010 would suddenly go to the non-default
                     * destination too, whereas it should have gone to the default.
                     *
                     * The only way to prevent this is to add the longer AIDs of the
                     * default route at the top of the table, so they will be matched first.
                     */
                    Set<String> defaultRouteAids = mAidRoutingTable.get(mDefaultRoute);
                    if (defaultRouteAids != null) {
                        for (String defaultRouteAid : defaultRouteAids) {
                            // Check whether there are any shorted AIDs routed to non-default
                            // TODO this is O(N^2) run-time complexity...
                            for (Map.Entry<String, Integer> aidEntry : mRouteForAid.entrySet()) {
                                String aid = aidEntry.getKey();
                                int route = aidEntry.getValue();
                                if (defaultRouteAid.startsWith(aid) && route != mDefaultRoute) {
                                    if (DBG) {
                                        Log.d(TAG, "configureRouting: Adding AID " + defaultRouteAid
                                                + " for default "
                                                + "route, because a conflicting shorter "
                                                + "AID will be added to the routing table");
                                    }
                                    aidRoutingTableCache.put(defaultRouteAid, aidMap.get(defaultRouteAid));
                                }
                            }
                        }
                    }
                }

                // Add AID entries for all non-default routes
                for (int i = 0; i < mAidRoutingTable.size(); i++) {
                    int route = mAidRoutingTable.keyAt(i);
                    if (route != mDefaultRoute) {
                        Set<String> aidsForRoute = mAidRoutingTable.get(route);
                        for (String aid : aidsForRoute) {
                            if (aid.endsWith("*")) {
                                if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                                    Log.e(TAG, "configureRouting: This device does not support "
                                            + "prefix AIDs.");
                                } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY) {
                                    if (DBG) {
                                        Log.d(TAG, "configureRouting: Routing prefix AID " + aid
                                                + " to route " + Integer.toString(route));
                                    }
                                    // Cut off '*' since controller anyway treats all AIDs as a prefix
                                    aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                                } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX ||
                                  mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                    if (DBG) {
                                        Log.d(TAG, "configureRouting: Routing prefix AID " + aid
                                                + " to route " + Integer.toString(route));
                                    }
                                    aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                                }
                            } else if (aid.endsWith("#")) {
                                if (mAidMatchingSupport == AID_MATCHING_EXACT_ONLY) {
                                    Log.e(TAG,
                                            "configureRouting: Device does not support subset "
                                                    + "AIDs but AID [" + aid + "] is registered");
                                } else if (mAidMatchingSupport == AID_MATCHING_PREFIX_ONLY ||
                                    mAidMatchingSupport == AID_MATCHING_EXACT_OR_PREFIX) {
                                    Log.e(TAG, "configureRouting: Device does not support subset "
                                            + "AIDs but AID [" + aid + "] is registered");
                                } else if (mAidMatchingSupport == AID_MATCHING_EXACT_OR_SUBSET_OR_PREFIX) {
                                    if (DBG) {
                                        Log.d(TAG, "configureRouting: Routing subset AID " + aid
                                                + " to route " + Integer.toString(route));
                                    }
                                    aidRoutingTableCache.put(aid.substring(0,aid.length() - 1), aidMap.get(aid));
                                }
                            } else {
                                if (DBG) {
                                    Log.d(TAG, "configureRouting: Routing exact AID " + aid
                                            + " to route " + Integer.toString(route));
                                }
                                aidRoutingTableCache.put(aid, aidMap.get(aid));
                            }
                        }
                    }
                }

                if (NfcService.getInstance().getNciVersion()
                        >= NfcService.getInstance().NCI_VERSION_2_0) {
                    String emptyAid = "";
                    AidEntry entry = new AidEntry();
                    int default_route_power_state;
                    entry.route = mDefaultRoute;
                    if (mDefaultRoute == ROUTE_HOST) {
                        entry.isOnHost = true;
                        default_route_power_state = RegisteredAidCache.POWER_STATE_SWITCH_ON
                                | RegisteredAidCache.POWER_STATE_SCREEN_ON_LOCKED;
                        Set<String> aidsForDefaultRoute = mAidRoutingTable.get(mDefaultRoute);
                        if (aidsForDefaultRoute != null) {
                            for (String aid : aidsForDefaultRoute) {
                                default_route_power_state |= aidMap.get(aid).power;
                            }
                        }
                    } else {
                        entry.isOnHost = false;
                        default_route_power_state = RegisteredAidCache.POWER_STATE_ALL;
                    }
                    if (mPowerEmptyAid != default_route_power_state) {
                        isPowerStateUpdated = true;
                    }
                    mPowerEmptyAid = default_route_power_state;
                    entry.aidInfo = RegisteredAidCache.AID_ROUTE_QUAL_PREFIX;
                    entry.power = default_route_power_state;
                    aidRoutingTableCache.put(emptyAid, entry);
                    if (DBG) Log.d(TAG, "configureRouting: Add emptyAid into AidRoutingTable");
                }

                // Register additional offhost AIDs when their support power states are
                // different from the default route entry
                if (mDefaultRoute != ROUTE_HOST) {
                    int default_route_power_state = RegisteredAidCache.POWER_STATE_ALL;
                    if (NfcService.getInstance().getNciVersion()
                            < NfcService.getInstance().NCI_VERSION_2_0) {
                        default_route_power_state =
                                RegisteredAidCache.POWER_STATE_ALL_NCI_VERSION_1_0;
                    }

                    Set<String> aidsForDefaultRoute = mAidRoutingTable.get(mDefaultRoute);
                    if (aidsForDefaultRoute != null) {
                        for (String aid : aidsForDefaultRoute) {
                            if (aidMap.get(aid).power != default_route_power_state) {
                                aidRoutingTableCache.put(aid, aidMap.get(aid));
                                isPowerStateUpdated = true;
                            }
                        }
                    }
                }

                // Unchecked Offhosts rout to host
                if (mDefaultRoute != ROUTE_HOST) {
                    Log.d(TAG, "configureRouting: check offHost route to host");
                    checkOffHostRouteToHost(aidRoutingTableCache);
                }

              if (calculateAidRouteSize(aidRoutingTableCache) <= mMaxAidRoutingTableSize ||
                    mRoutingOptionManager.isRoutingTableOverrided()) {
                  aidRouteResolved = true;
                  break;
              }
          }

            boolean mIsUnrouteRequired = checkUnrouteAid(prevRouteForAid, prevPowerForAid);
            boolean isRouteTableUpdated = checkRouteAid(prevRouteForAid, prevPowerForAid);
            boolean isRoutingOptionUpdated = checkRoutingOptionChanged(prevDefaultRoute,
                    prevDefaultIsoDepRoute, prevDefaultOffHostRoute);

            if (isPowerStateUpdated || isRouteTableUpdated || mIsUnrouteRequired
                    || isRoutingOptionUpdated || force) {
                if (aidRouteResolved) {
                    clearNfcRoutingTableLocked();
                    sendRoutingTable(isRoutingOptionUpdated, force);
                    int result = commit(aidRoutingTableCache, isOverrideOrRecover);
                    if (result != NfcOemExtension.COMMIT_ROUTING_STATUS_OK) {
                        NfcStatsLog.write(NfcStatsLog.NFC_ERROR_OCCURRED,
                                NfcStatsLog.NFC_ERROR_OCCURRED__TYPE__UNKNOWN, 0, 0);
                        return CONFIGURE_ROUTING_FAILURE_UNKNOWN;
                    }
                } else {
                    NfcStatsLog.write(NfcStatsLog.NFC_ERROR_OCCURRED,
                            NfcStatsLog.NFC_ERROR_OCCURRED__TYPE__AID_OVERFLOW, 0, 0);
                    Log.e(TAG, "configureRouting: RoutingTable unchanged because it's full, "
                            + "not updating");
                    return CONFIGURE_ROUTING_FAILURE_TABLE_FULL;
                }
            } else {
                Log.e(TAG, "configureRouting: All AIDs routing to mDefaultRoute, RoutingTable"
                        + " update is not required");
            }
        }
        return CONFIGURE_ROUTING_SUCCESS;
    }

    private int commit(HashMap<String, AidEntry> routeCache, boolean isOverrideOrRecover) {
        if (routeCache != null) {
            for (Map.Entry<String, AidEntry> aidEntry : routeCache.entrySet())  {
                int route = aidEntry.getValue().route;
                int aidType = aidEntry.getValue().aidInfo;
                String aid = aidEntry.getKey();
                int power = aidEntry.getValue().power;
                if (DBG)  {
                    Log.d(TAG, "commit: aid:" + aid + ",route:" + route
                        + ",aidtype:" + aidType + ", power state:" + power);
                }
                NfcService.getInstance().routeAids(aid, route, aidType, power);
            }
        }

        // And finally commit the routing
        return NfcService.getInstance().commitRouting(isOverrideOrRecover);
    }

    private void sendRoutingTable(boolean optionChanged, boolean force) {
        if (!mRoutingOptionManager.isRoutingTableOverrided()) {
            if (force || optionChanged) {
                Log.d(TAG, "sendRoutingTable");
                NfcService.getInstance().setIsoDepProtocolRoute(mDefaultIsoDepRoute);
                NfcService.getInstance().setTechnologyABFRoute(mDefaultOffHostRoute,
                        mDefaultFelicaRoute);
            }
        } else {
            Log.d(TAG, "sendRoutingTable: Routing table is override, "
                    + "Do not send the protocol, tech");
        }
    }

    /**
     * This notifies that the AID routing table in the controller
     * has been cleared (usually due to NFC being turned off).
     */
    public void onNfccRoutingTableCleared() {
        // The routing table in the controller was cleared
        // To stay in sync, clear our own tables.
        synchronized (mLock) {
            mAidRoutingTable.clear();
            mRouteForAid.clear();
            mPowerForAid.clear();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Routing table:");
        pw.println("    Default route: " + ((mDefaultRoute == 0x00) ? "host" : "secure element"));
        synchronized (mLock) {
            for (int i = 0; i < mAidRoutingTable.size(); i++) {
                Set<String> aids = mAidRoutingTable.valueAt(i);
                pw.println("    Routed to 0x" + Integer.toHexString(mAidRoutingTable.keyAt(i)) + ":");
                for (String aid : aids) {
                    pw.println("        \"" + aid + "\"");
                }
            }
        }
    }

    /**
     * Dump debugging information as a AidRoutingManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    void dumpDebug(ProtoOutputStream proto) {
        proto.write(AidRoutingManagerProto.DEFAULT_ROUTE, mDefaultRoute);
        synchronized (mLock) {
            for (int i = 0; i < mAidRoutingTable.size(); i++) {
                long token = proto.start(AidRoutingManagerProto.ROUTES);
                proto.write(AidRoutingManagerProto.Route.ID, mAidRoutingTable.keyAt(i));
                mAidRoutingTable.valueAt(i).forEach(aid -> {
                    proto.write(AidRoutingManagerProto.Route.AIDS, aid);
                });
                proto.end(token);
            }
        }
    }

    @VisibleForTesting
    public boolean isRoutingTableCleared() {
        return mAidRoutingTable.size() == 0 && mRouteForAid.isEmpty() && mPowerForAid.isEmpty();
    }
}
