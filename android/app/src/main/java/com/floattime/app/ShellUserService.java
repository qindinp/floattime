package com.floattime.app;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService 实现
 * 在特权进程中执行 shell 命令
 *
 * 事务码：
 * - 1: execute(command) -> result
 * - 16777115: destroy() -> void
 */
public class ShellUserService extends Binder {

    private static final String TAG = "ShellUserService";
    private static final String INTERFACE_TOKEN = "com.floattime.app.IShellService";

    // 事务码
    private static final int TRANSACTION_EXECUTE = 1;
    private static final int TRANSACTION_DESTROY = 16777115; // Shizuku 要求的 destroy 事务码

    public ShellUserService() {
        Log.d(TAG, "ShellUserService created");
        attachInterface(this, INTERFACE_TOKEN);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Log.d(TAG, "onTransact: code=" + code);

        switch (code) {
            case TRANSACTION_EXECUTE:
                data.enforceInterface(INTERFACE_TOKEN);
                String command = data.readString();
                Log.d(TAG, "Executing: " + command);
                String result = executeInternal(command);
                reply.writeNoException();
                reply.writeString(result);
                return true;

            case TRANSACTION_DESTROY:
                Log.d(TAG, "destroy called");
                reply.writeNoException();
                // Shizuku 要求: destroy 方法退出进程
                System.exit(0);
                return true;

            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /**
     * 执行 shell 命令
     */
    private String executeInternal(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            StringBuilder errOutput = new StringBuilder();
            while ((line = errReader.readLine()) != null) {
                errOutput.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            reader.close();
            errReader.close();

            if (exitCode != 0 && errOutput.length() > 0) {
                Log.w(TAG, "Command stderr: " + errOutput.toString().trim());
            }

            String result = output.toString().trim();
            Log.d(TAG, "Result: " + result);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Execute failed: " + e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }
}
