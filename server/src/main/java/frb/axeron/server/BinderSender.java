package frb.axeron.server;

import static android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_CACHED;
import static android.app.ActivityManagerHidden.UID_OBSERVER_GONE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_IDLE;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import frb.axeron.server.util.Logger;
import kotlin.collections.ArraysKt;
import moe.shizuku.server.IShizukuService;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;

public class BinderSender {

    private static final Logger LOGGER = new Logger("BinderSender");

    private static final String PERMISSION_MANAGER = "frb.axeron.manager.permission.MANAGER";

    private static final String SHIZUKU_PERMISSION_MANAGER = "moe.shizuku.manager.permission.MANAGER";

    private static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";

    private static IAxeronService axeronService;

    private static final Map<Integer, List<String>> UID_PACKAGE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> SENT_BINDERS = ConcurrentHashMap.newKeySet();

    private static List<String> getPackagesCached(int uid) {
        return UID_PACKAGE_CACHE.computeIfAbsent(uid, PackageManagerApis::getPackagesForUidNoThrow);
    }

    private static void sendBinder(int uid, int pid) throws RemoteException {
        String key = uid + ":" + pid;
        if (!SENT_BINDERS.add(key)) {
            LOGGER.v("Skip duplicate binder send for %s", key);
            return;
        }

        int userId = uid / 100000;

        List<String> packages = getPackagesCached(uid);
        if (packages.isEmpty())
            return;

        boolean isOurManager = packages.contains(ServerConstants.MANAGER_APPLICATION_ID);

        IShizukuService shizukuService = axeronService.getShizukuService();

        // 🔒 secure fast-path
        if (shizukuService == null && isOurManager) {
            LOGGER.d(
                    "Shizuku unavailable, uid %d belongs to manager, send binder directly",
                    uid
            );
            // 【v2.0.2 激活投递修复】投递失败则回滚「一次性」标记，使后续 uid/process 事件
            // （例如 App 解冻变为 active）可以重新投递，而不是永久放弃。
            if (!AxeronService.sendBinderToManagerResult(axeronService.asBinder(), userId)) {
                LOGGER.d("send binder to manager failed, rollback sent-mark for %s", key);
                resetBinderState(uid, pid);
            }
            return;
        }

        // Kalau bukan manager, dan shizuku null → STOP
        if (shizukuService == null)
            return;

        LOGGER.d(
                "sendBinder to uid %d: packages=%s",
                uid,
                TextUtils.join(", ", packages)
        );

        for (String packageName : packages) {
            try {
                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(
                        packageName,
                        PackageManager.GET_PERMISSIONS,
                        userId
                );
                if (pi == null || pi.requestedPermissions == null)
                    continue;

                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER)) {
                    boolean granted = pid == -1
                            ? PermissionManagerApis.checkPermission(PERMISSION_MANAGER, uid)
                            == PackageManager.PERMISSION_GRANTED
                            : ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid)
                            == PackageManager.PERMISSION_GRANTED;

                    if (granted) {
                        // 【v2.0.2 激活投递修复】同上：投递失败时回滚「一次性」标记。
                        if (!AxeronService.sendBinderToManagerResult(axeronService.asBinder(), userId)) {
                            LOGGER.d("send binder to manager failed, rollback sent-mark for %s", key);
                            resetBinderState(uid, pid);
                        }
                        return;
                    }

                } else if (ArraysKt.contains(pi.requestedPermissions, SHIZUKU_PERMISSION_MANAGER)) {
                    boolean granted = pid == -1
                            ? PermissionManagerApis.checkPermission(SHIZUKU_PERMISSION_MANAGER, uid)
                            == PackageManager.PERMISSION_GRANTED
                            : ActivityManagerApis.checkPermission(SHIZUKU_PERMISSION_MANAGER, pid, uid)
                            == PackageManager.PERMISSION_GRANTED;

                    if (granted) {
                        AxeronService.sendBinderToShizukuManager(
                                shizukuService.asBinder(),
                                userId
                        );
                        return;
                    }

                } else if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    // 【v2.0.2 激活投递修复】失败同样回滚「一次性」标记。
                    if (!AxeronService.sendBinderToUserAppResult(shizukuService.asBinder(), packageName, userId)) {
                        LOGGER.d("send binder to %s failed, rollback sent-mark for %s", packageName, key);
                        resetBinderState(uid, pid);
                    }
                    return;
                }

            } catch (Throwable e) {
                LOGGER.w(e, "sendBinder failed for package %s", packageName);
            }
            }
        }

        /**
         * 【v2.0.2 激活投递修复】回滚「一次性」标记。
         *
         * 原实现把 uid:pid（SENT_BINDERS）与 uid（UID_LIST）/ pid（PID_LIST）永久记为
         * 「已投递」：一旦首次投递失败（典型场景：server 在 App 的 attachApplication 时机
         * 投递，而 ContentProvider 尚未 publish；或 App 被系统冻结导致 provider 暂时取不到），
         * 此后 App 即使变为活跃也不会再投递 —— 表现就是「激活提示成功、界面不跳转」。
         */
        private static void resetBinderState(int uid, int pid) {
            SENT_BINDERS.remove(uid + ":" + pid);
            UidObserver.reset(uid);
            ProcessObserver.reset(pid);
        }


//    private static void sendBinder(int uid, int pid) {
//        String key = uid + ":" + pid;
//        if (!SENT_BINDERS.add(key)) {
//            LOGGER.v("Skip duplicate binder send for %s", key);
//            return;
//        }
//
//        List<String> packages = getPackagesCached(uid);
//        if (packages.isEmpty())
//            return;
//
//        LOGGER.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages));
//
//        int userId = uid / 100000;
//        for (String packageName : packages) {
//            try {
//                PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(packageName, PackageManager.GET_PERMISSIONS, userId);
//                if (pi == null || pi.requestedPermissions == null)
//                    continue;
//
//                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER)) {
//                    boolean granted;
//                    if (pid == -1)
//                        granted = PermissionManagerApis.checkPermission(PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED;
//                    else
//                        granted = ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED;
//
//                    if (granted) {
//                        AxeronService.sendBinderToManager(axeronService.asBinder(), userId);
//                        return;
//                    }
//                } else if (ArraysKt.contains(pi.requestedPermissions, SHIZUKU_PERMISSION_MANAGER)) {
//                    boolean granted;
//                    if (pid == -1)
//                        granted = PermissionManagerApis.checkPermission(SHIZUKU_PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED;
//                    else
//                        granted = ActivityManagerApis.checkPermission(SHIZUKU_PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED;
//
//                    if (granted) {
//                        IShizukuService shizukuService = axeronService.getShizukuService();
//                        if (shizukuService != null)
//                            AxeronService.sendBinderToShizukuManager(shizukuService.asBinder(), userId);
//                        return;
//                    }
//                } else if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
//                    IShizukuService shizukuService = axeronService.getShizukuService();
//                    if (shizukuService != null) {
//                        AxeronService.sendBinderToUserApp(shizukuService.asBinder(), packageName, userId);
//                    }
//                    return;
//                }
//            } catch (Throwable e) {
//                LOGGER.w(e, "sendBinder failed for package %s", packageName);
//            }
//        }
//    }

    public static void register(IAxeronService service) {
        axeronService = service;

        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserver());
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerProcessObserver");
        }

        int flags = UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_ACTIVE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags |= UID_OBSERVER_CACHED;
        }
        try {
            ActivityManagerApis.registerUidObserver(new UidObserver(), flags,
                    ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                    null);
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerUidObserver");
        }
    }

    private static class ProcessObserver extends ProcessObserverAdapter {

        private static final Set<Integer> PID_LIST = new HashSet<>();

        /**
         * 【v2.0.2 激活投递修复】允许该 pid 重新投递（配合 {@link #resetBinderState}）。
         */
        static void reset(int pid) {
            synchronized (PID_LIST) {
                PID_LIST.remove(pid);
            }
        }

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
            LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, foregroundActivities ? "true" : "false");

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return;
                }
                PID_LIST.add(pid);
            }

            sendBinder(uid, pid);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid);

            synchronized (PID_LIST) {
                PID_LIST.remove(pid);
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState);

            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return;
                }
                PID_LIST.add(pid);
            }

            sendBinder(uid, pid);
        }
    }

    private static class UidObserver extends UidObserverAdapter {

        private static final Set<Integer> UID_LIST = new HashSet<>();

        /**
         * 【v2.0.2 激活投递修复】允许该 uid 重新投递（配合 {@link #resetBinderState}）。
         */
        static void reset(int uid) {
            synchronized (UID_LIST) {
                UID_LIST.remove(uid);
            }
        }

        @Override
        public void onUidActive(int uid) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d", uid);

            uidStarts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, Boolean.toString(cached));

            if (!cached) {
                uidStarts(uid);
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidIdle: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidStarts(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidGone(uid);
        }

        private void uidStarts(int uid) throws RemoteException {
            synchronized (UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    LOGGER.v("Uid %d already starts", uid);
                    return;
                }
                UID_LIST.add(uid);
                LOGGER.v("Uid %d starts", uid);
            }

            sendBinder(uid, -1);
        }

        private void uidGone(int uid) {
            synchronized (UID_LIST) {
                UID_LIST.remove(uid);
                LOGGER.v("Uid %d dead", uid);
            }
        }
    }
}