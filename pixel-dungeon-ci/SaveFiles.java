/*
 * Pixel Dungeon modernization support.
 * Keeps the legacy Bundle schema unchanged while hardening file replacement.
 */
package com.watabou.pixeldungeon.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;

public final class SaveFiles {

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String BACKUP_SUFFIX = ".bak";

    private SaveFiles() {
    }

    public static void writeSafely(String fileName, Bundle bundle) throws IOException {
        File target = Game.instance.getFileStreamPath(fileName);
        File temp = Game.instance.getFileStreamPath(fileName + TEMP_SUFFIX);
        File backup = Game.instance.getFileStreamPath(fileName + BACKUP_SUFFIX);

        writeSynced(temp, bundle);

        if (backup.exists() && !backup.delete()) {
            temp.delete();
            throw new IOException("Unable to clear stale backup: " + fileName);
        }

        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            temp.delete();
            throw new IOException("Unable to stage previous save: " + fileName);
        }

        if (!temp.renameTo(target)) {
            if (hadTarget && backup.exists()) {
                backup.renameTo(target);
            }
            temp.delete();
            throw new IOException("Unable to finalize save: " + fileName);
        }

        if (backup.exists()) {
            backup.delete();
        }
    }

    public static FileInputStream openForRead(String fileName) throws IOException {
        File target = Game.instance.getFileStreamPath(fileName);
        File backup = Game.instance.getFileStreamPath(fileName + BACKUP_SUFFIX);
        File temp = Game.instance.getFileStreamPath(fileName + TEMP_SUFFIX);

        if (!target.exists() && backup.exists()) {
            backup.renameTo(target);
        }
        if (!target.exists() && temp.exists()) {
            temp.renameTo(target);
        }

        return new FileInputStream(target);
    }

    private static void writeSynced(File file, Bundle bundle) throws IOException {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, false);
            if (!Bundle.write(bundle, output)) {
                throw new IOException("Failed to serialize save: " + file.getName());
            }
            output.getFD().sync();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
