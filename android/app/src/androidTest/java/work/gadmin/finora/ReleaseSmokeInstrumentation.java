package work.gadmin.finora;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Platform-only runner: test libraries can reference AndroidX methods removed from release APKs. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private boolean testCamera;
    private String organization;
    private String receiptUrl;
    private boolean submitReceipt;
    private boolean captureReceipt;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        testCamera = "true".equals(arguments.getString("camera"));
        organization = arguments.getString("organization");
        receiptUrl = arguments.getString("receiptUrl");
        submitReceipt = "true".equals(arguments.getString("submitReceipt"));
        captureReceipt = "true".equals(arguments.getString("captureReceipt"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            ComponentName service = new ComponentName(getTargetContext(),
                    "com.google.mlkit.common.internal.MlKitComponentDiscoveryService");
            Bundle metadata = getTargetContext().getPackageManager()
                    .getServiceInfo(service, PackageManager.GET_META_DATA).metaData;
            int count = 0;
            String prefix = "com.google.firebase.components:";
            for (String name : metadata.keySet()) {
                if (name.startsWith(prefix)) {
                    getTargetContext().getClassLoader().loadClass(name.substring(prefix.length()))
                            .getDeclaredConstructor().newInstance();
                    count++;
                }
            }
            if (count < 3) throw new AssertionError("Missing ML Kit registrars");
            result.putString("registrars", "PASS: " + count + " release constructors");
            if (testCamera) {
                startActivitySync(new Intent(Intent.ACTION_MAIN)
                        .setClassName(getTargetContext(), "work.gadmin.finora.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                // Preserve the existing account and drafts. Keep paper QR codes away during camera smoke.
                if (organization != null && awaitNode(organization, 8000) != null) click(organization);
                click("Сканировать QR");
                requireNode("QR-код чека");
                requireNode("Включить фонарик");
                SystemClock.sleep(2500);
                requireNode("QR-код чека");
                if (find("Не удалось запустить QR-сканер. Сфотографируйте чек или вставьте ссылку на чек.") != null)
                    throw new AssertionError("QR initialization failed");
                click("Закрыть камеру");
                click("Сфотографировать");
                requireNode("Снять чек");
                SystemClock.sleep(2000);
                requireNode("Снять чек");
                click("Закрыть камеру");
                result.putString("camera", "PASS: release QR and photo preview on device");
            }
            if (receiptUrl != null) {
                if (organization == null || !organization.startsWith("Android QA "))
                    throw new AssertionError("Receipt acceptance requires an isolated Android QA organization");
                startActivitySync(new Intent(Intent.ACTION_MAIN)
                        .setClassName(getTargetContext(), "work.gadmin.finora.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                click(organization);
                SystemClock.sleep(1500);
                if (find("Распознать чек") != null) {
                    click("Распознать чек");
                } else {
                    click("Вставить ссылку на чек");
                    setFirstEditable(receiptUrl);
                    click("Добавить");
                }
                requireNode("Электронный чек");
                if (submitReceipt || captureReceipt) {
                    SystemClock.sleep(2500);
                    click("Распознать этот чек");
                    if (awaitNode("Проверить", 150000) == null)
                        throw new AssertionError("Receipt did not reach review on the server");
                    if (find("В учёте") != null)
                        throw new AssertionError("Receipt was posted before confirmation");
                    if (!submitReceipt) {
                        snapshot("receipt-preview.png");
                        result.putString("receipt", "PASS: phone page capture and server preview without posting");
                        finish(Activity.RESULT_OK, result);
                        return;
                    }
                    scrollTo("Исправить данные и позиции");
                    click("Исправить данные и позиции");
                    requireNode("Исправить чек");
                    setFirstEditable("Mobile QA corrected");
                    snapshot("receipt-editor.png");
                    // This parking fixture has a 60 MDL line and a 30 MDL discount.
                    scrollTo("Сумма строки");
                    setLastEditable("30.00");
                    click("Подтвердить и сохранить");
                    requireNode("Сохранить исправленный чек?");
                    click("Сохранить расход");
                    requireNode("В учёте");
                    requireNode("Mobile QA corrected");
                    snapshot("receipt-posted.png");
                    result.putString("receipt", "PASS: phone page capture, server review, edit and explicit posting");
                } else {
                    long deadline = SystemClock.uptimeMillis() + 45000;
                    do {
                        AccessibilityNodeInfo node = find("Распознать этот чек");
                        while (node != null && !node.isClickable()) node = node.getParent();
                        if (node != null && node.isEnabled()) break;
                        if (SystemClock.uptimeMillis() >= deadline)
                            throw new AssertionError("Receipt page did not load on phone");
                        SystemClock.sleep(200);
                    } while (true);
                    result.putString("receiptPage", "PASS: HTTPS receipt page loaded by the phone");
                    snapshot("receipt-page.png");
                }
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void snapshot(String name) throws java.io.IOException {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(
                new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = firstEditable(node.getChild(i));
            if (match != null) return match;
        }
        return null;
    }

    private void setFirstEditable(String value) {
        AccessibilityNodeInfo field = null;
        long deadline = SystemClock.uptimeMillis() + 8000;
        do {
            field = firstEditable(getUiAutomation().getRootInActiveWindow());
            if (field != null) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        if (field == null) throw new AssertionError("No editable receipt field");
        Bundle input = new Bundle();
        input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            throw new AssertionError("Cannot edit receipt field");
        SystemClock.sleep(250);
    }

    private AccessibilityNodeInfo lastEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo result = node.isEditable() ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = lastEditable(node.getChild(i));
            if (child != null) result = child;
        }
        return result;
    }

    private void setLastEditable(String value) {
        AccessibilityNodeInfo field = lastEditable(getUiAutomation().getRootInActiveWindow());
        if (field == null) throw new AssertionError("Missing line amount field");
        Bundle input = new Bundle();
        input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            throw new AssertionError("Cannot correct receipt line amount");
        SystemClock.sleep(250);
    }

    private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = scrollable(node.getChild(i));
            if (match != null) return match;
        }
        return null;
    }

    private void scrollTo(String label) {
        for (int i = 0; i < 30; i++) {
            if (find(label) != null) return;
            AccessibilityNodeInfo list = scrollable(getUiAutomation().getRootInActiveWindow());
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            SystemClock.sleep(250);
        }
        requireNode(label);
    }

    private AccessibilityNodeInfo find(String label) {
        return findIn(getUiAutomation().getRootInActiveWindow(), label);
    }

    private AccessibilityNodeInfo findIn(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.contentEquals(node.getText() == null ? "" : node.getText())
                || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))
            return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findIn(node.getChild(i), label);
            if (match != null) return match;
        }
        return null;
    }

    private AccessibilityNodeInfo awaitNode(String label, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo node = find(label);
            if (node != null) return node;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private AccessibilityNodeInfo requireNode(String label) {
        AccessibilityNodeInfo node = awaitNode(label, 15000);
        if (node == null) throw new AssertionError("Missing UI control: " + label);
        return node;
    }

    private void click(String label) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        do {
            AccessibilityNodeInfo node = find(label);
            while (node != null && !node.isClickable()) node = node.getParent();
            if (node != null && node.isEnabled()
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Cannot activate UI control: " + label);
    }
}
