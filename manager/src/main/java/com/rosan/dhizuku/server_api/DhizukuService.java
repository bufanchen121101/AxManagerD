package com.rosan.dhizuku.server_api;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.rosan.dhizuku.aidl.IDhizuku;
import com.rosan.dhizuku.aidl.IDhizukuClient;
import com.rosan.dhizuku.aidl.IDhizukuRemoteProcess;
import com.rosan.dhizuku.aidl.IDhizukuUserServiceConnection;
import com.rosan.dhizuku.shared.DhizukuVariables;

import java.util.Arrays;

/**
 * Dhizuku 兼容 Daemon 服务端基类（简化移植版）。
 *
 * 与官方 server_api.DhizukuService 的差异：
 * - 去掉了对 com.rosan.app_process.AppProcess（RemoteProcess / UserServiceConnections）的依赖，
 *   因为这些高级功能（远程进程 / UserService）依赖 AppProcess 本地库，AxManagerD 不引入。
 *   remoteProcess / bindUserService / unbindUserService 系列一律返回 null 或空实现，
 *   调用方（Hail / InstallerX 等）主要通过 binderWrapper + ownerComponent + delegated scopes，
 *   不依赖远程进程。
 * - 保留了核心的 enforceCallingPermission + onRemoteTransact（binderWrapper 转发）机制，
 *   这是第三方 app 通过 Dhizuku API 代理执行 Device Owner 操作的关键。
 *
 * 子类需实现 {@link #checkCallingPermission(String, int, int)} 做授权校验。
 */
@SuppressWarnings({"RedundantThrows", "unused"})
public abstract class DhizukuService extends IDhizuku.Stub {
    protected Context mContext;
    protected DevicePolicyManager mManager;
    protected ComponentName mAdmin;
    protected IDhizukuClient mClient;

    public DhizukuService(@NonNull Context context, @Nullable ComponentName admin, @Nullable IDhizukuClient client) {
        mContext = context;
        mManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mAdmin = admin;
        mClient = client;
    }

    @Override
    public int getVersionCode() {
        return Variables.SERVICE_VERSION_CODE;
    }
    @Override
    public String getVersionName() {
        return String.valueOf(Variables.SERVICE_VERSION_CODE);
    }

    @Override
    public boolean isPermissionGranted() {
        try {
            enforceCallingPermission(null);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    /**
     * 授权校验核心：子类实现，决定某个 uid/pid 是否有权调用特权方法。
     */
    public abstract boolean checkCallingPermission(String func, int callingUid, int callingPid);

    public final void enforceCallingPermission(String func) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingUid == android.os.Process.myUid()) return;

        if (checkCallingPermission(func, callingUid, callingPid)) return;

        throw new SecurityException("Permission Denial: " + func
                + " is not allowed from pid="
                + callingPid + ", uid=" + callingUid);
    }

    /**
     * 转发原始 Binder transact：binderWrapper 的核心。
     * 直接将调用转发到 SystemServer 的真实 Binder（无 AppProcess，故直接 transact）。
     */
    public boolean onRemoteTransact(IBinder binder, int code, Parcel data, Parcel reply, int flags) {
        enforceCallingPermission("remote_transact");
        try {
            return binder.transact(code, data, reply, flags);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == DhizukuVariables.TRANSACT_CODE_REMOTE_BINDER) {
            Parcel remoteData = Parcel.obtain();
            try {
                data.enforceInterface(DhizukuVariables.BINDER_DESCRIPTOR);
                IBinder binder = data.readStrongBinder();
                int remoteCode = data.readInt();
                int remoteFlags = data.readInt();
                remoteData.appendFrom(data, data.dataPosition(), data.dataAvail());
                return onRemoteTransact(binder, remoteCode, remoteData, reply, remoteFlags);
            } finally {
                remoteData.recycle();
            }
        }
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public IDhizukuRemoteProcess remoteProcess(String[] cmd, String[] env, String dir) throws RemoteException {
        // 简化版：不依赖 AppProcess，远程进程功能不可用。
        enforceCallingPermission("remote_process");
        return null;
    }

    @Override
    public void bindUserService(IDhizukuUserServiceConnection connection, Bundle bundle) throws RemoteException {
        enforceCallingPermission("bind_user_service");
        // 简化版：不依赖 AppProcess，UserService 功能不可用。
    }

    @Override
    public void unbindUserService(Bundle bundle) throws RemoteException {
        enforceCallingPermission("unbind_user_service");
    }

    @Override
    public void unbindUserServiceByConnection(IDhizukuUserServiceConnection connection, Bundle bundle) throws RemoteException {
        enforceCallingPermission("unbind_user_service");
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Override
    public String[] getDelegatedScopes(String packageName) throws RemoteException {
        enforceCallingPermission("get_delegated_scopes");
        if (mAdmin == null) return new String[0];
        return mManager.getDelegatedScopes(mAdmin, packageName).toArray(new String[0]);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Override
    public void setDelegatedScopes(String packageName, String[] scopes) throws RemoteException {
        enforceCallingPermission("set_delegated_scopes");
        if (mAdmin == null) return;
        mManager.setDelegatedScopes(mAdmin, packageName, Arrays.asList(scopes));
    }
}