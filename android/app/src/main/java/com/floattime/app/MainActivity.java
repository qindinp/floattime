package com.floattime.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主界面 - 最简化版本，排除闪退原因
 */
public class MainActivity extends AppCompatActivity {

    private Button startBtn;
    private Button stopBtn;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_main);
            
            startBtn = findViewById(R.id.startBtn);
            stopBtn = findViewById(R.id.stopBtn);
            statusText = findViewById(R.id.statusText);

            if (startBtn != null) {
                startBtn.setOnClickListener(v -> {
                    Toast.makeText(this, "启动按钮被点击", Toast.LENGTH_SHORT).show();
                    // TODO: 权限检查后启动服务
                });
            }
            
            if (stopBtn != null) {
                stopBtn.setOnClickListener(v -> {
                    Toast.makeText(this, "停止按钮被点击", Toast.LENGTH_SHORT).show();
                });
            }
            
            if (statusText != null) {
                statusText.setText("⭕ 服务未启动");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
