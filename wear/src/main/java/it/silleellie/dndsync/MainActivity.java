package it.silleellie.dndsync;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import it.silleellie.dndsync.wear.R;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new MainFragment())
                    .commit();
        }
    }
}