package home.phonelock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ScreenGuardTest {
    private static final String G = "com.google.android.gms";
    private static final long NOW = 1_000_000L;
    private final Map<String, String> none = new HashMap<>();

    @Test
    public void otherAppsAreIgnored() {
        assertEquals(ScreenGuard.Verdict.IGNORE, ScreenGuard.classify("com.google.android.calendar",
                "com.android.calendar.AllInOneActivity", none, NOW));
        // A dialog or view inside a Google services screen isn't a screen of its own.
        assertEquals(ScreenGuard.Verdict.IGNORE, ScreenGuard.classify(G, "android.app.Dialog", none, NOW));
    }

    @Test
    public void knownEscapesAreBlocked() {
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".accountsettings.ui.AccountDetailsActivity", none, NOW));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".googlehelp.helpactivities.HelpActivity", none, NOW));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".nearby.sharing.main.MainActivity", none, NOW));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".app.settings.GoogleSettingsActivity", none, NOW));
    }

    @Test
    public void neededScreensAreAllowed() {
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".common.account.AccountPickerActivity", none, NOW));
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".location.settings.LocationSettingsCheckerActivity", none, NOW));
    }

    @Test
    public void unknownScreensAlert() {
        assertEquals(ScreenGuard.Verdict.ALERT, ScreenGuard.classify(G, G + ".games.ui.SomethingActivity", none, NOW));
        // An exact entry doesn't match by prefix.
        assertEquals(ScreenGuard.Verdict.ALERT, ScreenGuard.classify(G, G + ".quickstart.xos.ui.XosGoogleSettingsActivityExtra", none, NOW));
    }

    @Test
    public void parentDecisionsWin() {
        Map<String, String> rules = new HashMap<>();
        rules.put(G + ".games.ui.SomethingActivity", "ok");
        rules.put(G + ".common.account.AccountPickerActivity", "block");
        rules.put(G + ".googlehelp.helpactivities.HelpActivity", "ok");
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".games.ui.SomethingActivity", rules, NOW));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".common.account.AccountPickerActivity", rules, NOW));
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".googlehelp.helpactivities.HelpActivity", rules, NOW));
    }

    @Test
    public void areaDecisionsCoverTheirScreens() {
        Map<String, String> rules = new HashMap<>();
        rules.put("area:account", "ok");
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".accountsettings.ui.AccountDetailsActivity", rules, NOW));
        // Other areas stay blocked.
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".googlehelp.helpactivities.HelpActivity", rules, NOW));
        // A screen's own decision wins over its area's.
        rules.put(G + ".accountsettings.ui.AccountDetailsActivity", "block");
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".accountsettings.ui.AccountDetailsActivity", rules, NOW));
    }

    @Test
    public void timedAllowsRunOut() {
        Map<String, String> rules = new HashMap<>();
        rules.put("area:quickshare", "ok:" + (NOW + 60_000));
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".nearby.sharing.main.MainActivity", rules, NOW));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".nearby.sharing.main.MainActivity", rules, NOW + 60_000));
        rules.put(G + ".games.ui.SomethingActivity", "ok:" + (NOW - 1));
        assertEquals(ScreenGuard.Verdict.ALERT, ScreenGuard.classify(G, G + ".games.ui.SomethingActivity", rules, NOW));
    }

    @Test
    public void shortNames() {
        assertEquals("accountsettings › AccountDetails", ScreenGuard.shortName(G + ".accountsettings.ui.AccountDetailsActivity"));
        assertEquals("Foo", ScreenGuard.shortName(G + ".FooActivity"));
    }
}
