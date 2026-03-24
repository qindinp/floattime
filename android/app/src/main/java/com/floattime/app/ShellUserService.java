package com.floattime.app;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService 实现
 * 在特权进程中执行 shell 命令
 */
public class ShellUserService extends IShellService.Stub {

    private static final String TAG = "ShellUserService";

    @Override
    public String execute(String command) throws RemoteException {
        try {
            Log.d(TAG, "Executing: " + command);
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
            throw new RemoteException("Execute failed: " + e.getMessage());
        }
    }

    @Override
    public void destroy() throws RemoteException {
        Log.d(TAG, "destroy called");
        // Shizuku 要求: destroy 方法退出进程
        System.exit(0);
    }
}
