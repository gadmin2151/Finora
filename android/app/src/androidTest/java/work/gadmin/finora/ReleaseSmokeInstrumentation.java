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

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        testCamera = "true".equals(arguments.getString("camera"));
        organization = arguments.getString("organization");
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
                // Choose the existing organization only; never sign out, upload, or erase drafts.
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
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
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
