package com.armsx3;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Asset extraction.
 *
 * Replaces the one static helper ARMSX2's runtime borrowed from the third-party
 * kr.co.iefriends.pcsx2.MainActivity (copyFile). Clean-room, and it keeps the
 * whole third-party package out of the tree.
 */
public final class AssetUtil {

    private AssetUtil() {}

    /**
     * Copy a single asset to {@code destPath}, creating parent directories.
     * Returns false rather than throwing so a missing optional asset does not
     * take down first-run setup.
     */
    public static boolean copyFile(Context context, String assetPath, String destPath) {
        File dest = new File(destPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
