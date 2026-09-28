package com.termux.app.nativeui;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class NativeRuntimeManager {

    private static final String ASSET = "native-ai/agent-runtime";
    private static final String DIR = ".ai-workspace/bin";
    private static final String NAME = "agent-runtime";

    private NativeRuntimeManager() {}

    public static File prepare(Context context) throws IOException {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR, DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create runtime directory");
        }

        File output = new File(dir, NAME);
        try (InputStream input = context.getAssets().open(ASSET);
             FileOutputStream stream = new FileOutputStream(output, false)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                stream.write(buffer, 0, read);
            }
            stream.flush();
        }

        if (!output.setExecutable(true, false)) {
            throw new IOException("Unable to make runtime executable");
        }
        return output;
    }
}
