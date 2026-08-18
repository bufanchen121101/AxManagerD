package rikka.rish;

import android.os.Parcel;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RishTerminal {

    private static final String TAG = "RishTerminal";
    private final String[] argv;
    private final byte tty;
    private FileDescriptor[] stdin;
    private FileDescriptor[] stdout;
    private FileDescriptor[] stderr;
    private int ttyFd = -1;

    private static final int NOT_EXITED = Integer.MIN_VALUE;
    private final AtomicInteger exitCode = new AtomicInteger(NOT_EXITED);

    public RishTerminal(String[] argv) throws ErrnoException, RemoteException, IOException, InterruptedException {
        this.tty = prepare();

        if (this.tty == 0) {
            if (argv == null || argv.length == 0) {
                this.argv = new String[]{"/system/bin/sh"};
            } else {
                String[] newArgv = new String[argv.length + 1];
                newArgv[0] = "/system/bin/sh";
                System.arraycopy(argv, 0, newArgv, 1, argv.length);
                this.argv = newArgv;
            }

            RemoteProcess process = RishConfig.newProcess(this.argv);

            new Thread(() -> {
                try (
                        InputStream in = process.getInputStream();
                        OutputStream out = System.out
                ) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }, "rish-stdout").start();

            new Thread(() -> {
                try (
                        InputStream in = process.getErrorStream();
                        OutputStream out = System.err
                ) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }, "rish-stderr").start();

            new Thread(() -> {
                try {
                    int code = process.waitFor();
                    exitCode.set(code);

                    try {
                        process.getOutputStream().close();
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            }, "rish-waiter").start();


            InputStream in = System.in;
            OutputStream out = process.getOutputStream();
            byte[] buf = new byte[4096];

            while (exitCode.get() == NOT_EXITED) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n == -1) break;

                    out.write(buf, 0, n);
                    out.flush();
                } else {
                    Thread.sleep(10);
                }
            }
            return;
        }

        this.argv = argv;
        createHost();
    }

    public static int getFd(FileDescriptor[] fileDescriptor, int i) {
        if (fileDescriptor == null) {
            return -1;
        }
        return FileDescriptors.getFd(fileDescriptor[i]);
    }

    public static void closeFd(FileDescriptor[] fileDescriptor, int i) {
        if (fileDescriptor == null) {
            return;
        }
        FileDescriptors.closeSilently(fileDescriptor[i]);
    }

    private static native byte prepare();

    private static native int start(byte tty, int stdin, int stdout, int stderr);

    private static native long waitForWindowSizeChange(int fd);

    private static native void waitForProcessExit();

    private void createHost() throws ErrnoException, RemoteException {
        Log.d(TAG, "createHost");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            list.add(entry.getKey() + "=" + entry.getValue());
        }
        String[] env = list.toArray(new String[0]);

        String dir = new File("").getAbsolutePath();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            data.writeByte(tty);
            stdin = Os.pipe();
            data.writeFileDescriptor(stdin[0]);
            stdout = Os.pipe();
            data.writeFileDescriptor(stdout[1]);
            if ((tty & RishConstants.ATTY_ERR) == 0) {
                stderr = Os.pipe();
                data.writeFileDescriptor(stderr[1]);
            }
            data.writeStringArray(argv);
            data.writeStringArray(env);
            data.writeString(dir);
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_createHost), data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();

            closeFd(stdin, 0);
            closeFd(stdout, 1);
            closeFd(stderr, 1);
        }
    }

    public void start() {
        Log.d(TAG, "start");

        ttyFd = start(tty, getFd(stdin, 1), getFd(stdout, 0), getFd(stderr, 0));

        if (ttyFd != -1) {
            new Thread(() -> {
                while (true) {
                    Log.d(TAG, "waitForWindowSizeChange");

                    try {
                        long size = waitForWindowSizeChange(ttyFd);
                        setWindowSize(size);
                    } catch (Throwable e) {
                        Log.w(TAG, Log.getStackTraceString(e));
                    }
                }
            }).start();
        }
    }

    private void setWindowSize(long size) throws RemoteException {
        Log.d(TAG, "setWindowSize");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            data.writeLong(size);
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_setWindowSize), data, null, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int requestExitCode() throws RemoteException {
        Log.d(TAG, "requestExitCode");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken());
            RishConfig.getBinder().transact(RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode), data, null, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public int waitFor() {
        Log.d(TAG, "waitFor");

        if (ttyFd != -1) {
            waitForProcessExit();
        }
        try {
            exitCode.set(requestExitCode());
        } catch (Throwable e) {
            Log.w(TAG, Log.getStackTraceString(e));
            exitCode.set(-1);
        }
        return exitCode.get();
    }

    public int getExitCode() {
        return exitCode.get();
    }
}
