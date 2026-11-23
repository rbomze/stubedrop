package com.stubedrop.sender;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Target of Share → S-Tube Drop Sender: sends the shared YouTube link straight to the TV. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        String link = LinkExtractor.extract(text);
        if (link == null) {
            Toast.makeText(this, "S-Tube Drop: no YouTube link found in the shared text.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final DropClient.Config cfg = Store.load(this);
        final Handler ui = new Handler(Looper.getMainLooper());
        Toast.makeText(this, "Sending to TV…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DropClient.Result r = DropClient.send(cfg, link);
            ui.post(() -> done(r));
        }).start();
    }

    private void done(DropClient.Result r) {
        switch (r.kind) {
            case OK:
                Toast.makeText(this, "✓ " + r.msg, Toast.LENGTH_SHORT).show();
                break;
            case CONFIG: case TRUST: case CERT_CHANGED: case AUTH:
                // needs the user's attention: open the settings with the problem shown
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_WARNING, r.msg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                break;
            default:
                Toast.makeText(this, "⚠ " + r.msg, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
