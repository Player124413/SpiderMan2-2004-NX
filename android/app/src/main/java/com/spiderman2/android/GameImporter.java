package com.spiderman2.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.UUID;

/**
 * Imports an owner-selected Xbox disc image and unpacks its XDVDFS tree into the
 * selected game directory. The source is copied into a staging folder beside
 * the destination first, so providers such as Downloads, USB and cloud drives do not need to
 * expose a filesystem path to native code.
 */
final class GameImporter {
    static final int REQUEST = 0x534D32;
    private static final int BUFFER_BYTES = 4 << 20;
    private static final long SPACE_MARGIN = 96L << 20;

    interface Listener {
        void onCompleted(boolean success, File gameDirectory);
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile boolean copying;

    GameImporter(Activity activity) {
        this.activity = activity;
    }

    boolean pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Disc images are commonly assigned no MIME type by file managers.
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST);
            return true;
        } catch (ActivityNotFoundException e) {
            message("Import game image", "No file picker is available on this device.", null);
            return false;
        }
    }

    void importAndExtract(Uri source, File destination, Listener listener) {
        String pickedName = displayName(source);
        String extension = imageExtension(pickedName);
        if (extension == null) {
            message("Unsupported file", "Choose an original-Xbox .iso, .xiso, .cci or .cso disc image. "
                    + "Archives such as zip, 7z and rar must be extracted first.", () -> listener.onCompleted(false, destination));
            return;
        }
        if (destination.exists()) {
            message("Game folder already exists", "Choose a new empty game-folder path before importing. Existing game files are never overwritten.",
                    () -> listener.onCompleted(false, destination));
            return;
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            message("Cannot create game folder", "The selected game-folder path cannot be created.", () -> listener.onCompleted(false, destination));
            return;
        }

        long sourceSize = size(source);
        // The staging image and the unpacked files coexist for a short time. A
        // plain XISO is normally close to its extracted size; compressed images
        // can expand, so reserve two image sizes plus a small metadata margin.
        long needed = sourceSize > 0 ? saturatedDouble(sourceSize, SPACE_MARGIN) : 0;
        if (needed > 0 && parent.getUsableSpace() < needed) {
            message("Not enough free space", String.format(Locale.US,
                    "Importing and unpacking this image needs about %.1f GB free; only %.1f GB is available.",
                    needed / 1e9, parent.getUsableSpace() / 1e9), () -> listener.onCompleted(false, destination));
            return;
        }

        File work = new File(parent, "." + destination.getName() + ".import-" + UUID.randomUUID());
        File image = new File(work, "disc" + extension);
        File part = new File(work, image.getName() + ".part");
        File extracted = new File(work, "extracted");
        if (!work.mkdirs()) {
            message("Cannot start import", "A temporary import folder could not be created.", () -> listener.onCompleted(false, destination));
            return;
        }

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(sourceSize <= 0);
        TextView progressText = new TextView(activity);
        progressText.setText("Preparing disc image…");
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, 0);
        content.addView(bar);
        content.addView(progressText);

        cancelled = false;
        copying = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Importing " + pickedName)
                .setView(content)
                .setCancelable(false)
                .setNegativeButton("Cancel", (ignored, ignoredWhich) -> {
                    if (copying) cancelled = true;
                })
                .show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            String error = null;
            boolean completed = false;
            try {
                copyContentUri(source, part, sourceSize, bar, progressText);
                if (cancelled) {
                    error = "Import cancelled.";
                } else if (!part.renameTo(image)) {
                    error = "The disc image could not be finalized.";
                } else {
                    copying = false;
                    main.post(() -> {
                        bar.setIndeterminate(true);
                        progressText.setText("Unpacking game files…");
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    });
                    error = NativeBridge.nativeExtractDisc(image.getAbsolutePath(), extracted.getAbsolutePath());
                    if (error == null || error.isEmpty()) {
                        if (!extracted.renameTo(destination)) error = "The extracted game folder could not be put in place.";
                        else completed = true;
                    }
                }
            } catch (Exception e) {
                error = "The selected file could not be read: " + safeMessage(e);
            } finally {
                copying = false;
                // On success only the temporary ISO remains because extracted was moved out first.
                deleteTree(work);
            }

            final boolean success = completed;
            final String reason = error;
            main.post(() -> {
                if (!activity.isFinishing()) dialog.dismiss();
                if (success) {
                    listener.onCompleted(true, destination);
                } else if (reason != null && !reason.equals("Import cancelled.")) {
                    message("Import failed", reason, () -> listener.onCompleted(false, destination));
                } else {
                    listener.onCompleted(false, destination);
                }
            });
        }, "SpiderMan2DiscImport").start();
    }

    private void copyContentUri(Uri source, File output, long expectedSize, ProgressBar bar, TextView text) throws Exception {
        try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(source, "r");
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             FileChannel in = input.getChannel();
             FileOutputStream stream = new FileOutputStream(output);
             FileChannel out = stream.getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
            long copied = 0;
            long shown = 0;
            while (!cancelled && in.read(buffer) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) copied += out.write(buffer);
                buffer.clear();
                if (copied - shown >= (16L << 20)) {
                    shown = copied;
                    long amount = copied;
                    main.post(() -> showProgress(bar, text, amount, expectedSize));
                }
            }
            stream.getFD().sync();
            if (!cancelled && expectedSize > 0 && copied != expectedSize)
                throw new IllegalStateException("The copy stopped before the end of the image.");
        }
    }

    private static void showProgress(ProgressBar bar, TextView text, long copied, long size) {
        if (size > 0) {
            bar.setProgress((int) Math.min(1000L, copied * 1000L / size));
            text.setText(String.format(Locale.US, "Copying image: %.2f of %.2f GB", copied / 1e9, size / 1e9));
        } else {
            text.setText(String.format(Locale.US, "Copying image: %.2f GB", copied / 1e9));
        }
    }

    private static long saturatedDouble(long bytes, long margin) {
        if (bytes > (Long.MAX_VALUE - margin) / 2) return Long.MAX_VALUE;
        return bytes * 2 + margin;
    }

    private static String imageExtension(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[] {".iso", ".xiso", ".cci", ".cso"})
            if (lower.endsWith(extension)) return extension;
        return null;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                return new File(cursor.getString(0)).getName();
        } catch (Exception ignored) {
            // Fall back to the provider path below.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "disc image" : new File(last).getName();
    }

    private long size(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
            // Indeterminate progress is used for providers without a size.
        }
        return -1;
    }

    private static String safeMessage(Exception exception) {
        String text = exception.getMessage();
        return text == null || text.isEmpty() ? "unknown storage error" : text;
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        // This is staging data only. A failed delete merely leaves a removable .import folder.
        file.delete();
    }

    private void message(String title, String body, Runnable after) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .setOnDismissListener(ignored -> {
                    if (after != null) after.run();
                })
                .show();
    }
}
