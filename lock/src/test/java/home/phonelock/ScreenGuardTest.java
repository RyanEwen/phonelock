package home.phonelock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ScreenGuardTest {
    private static final String G = "com.google.android.gms";
    private final Map<String, String> none = new HashMap<>();

    @Test
    public void otherAppsAreIgnored() {
        assertEquals(ScreenGuard.Verdict.IGNORE, ScreenGuard.classify("com.google.android.calendar",
                "com.android.calendar.AllInOneActivity", none));
        // A dialog or view inside a Google services screen isn't a screen of its own.
        assertEquals(ScreenGuard.Verdict.IGNORE, ScreenGuard.classify(G, "android.app.Dialog", none));
    }

    @Test
    public void knownEscapesAreBlocked() {
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".accountsettings.ui.AccountDetailsActivity", none));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".googlehelp.helpactivities.HelpActivity", none));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".nearby.sharing.main.MainActivity", none));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".app.settings.GoogleSettingsActivity", none));
    }

    @Test
    public void neededScreensAreAllowed() {
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".common.account.AccountPickerActivity", none));
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".location.settings.LocationSettingsCheckerActivity", none));
    }

    @Test
    public void unknownScreensAlert() {
        assertEquals(ScreenGuard.Verdict.ALERT, ScreenGuard.classify(G, G + ".games.ui.SomethingActivity", none));
        // An exact entry doesn't match by prefix.
        assertEquals(ScreenGuard.Verdict.ALERT, ScreenGuard.classify(G, G + ".quickstart.xos.ui.XosGoogleSettingsActivityExtra", none));
    }

    @Test
    public void parentDecisionsWin() {
        Map<String, String> rules = new HashMap<>();
        rules.put(G + ".games.ui.SomethingActivity", "ok");
        rules.put(G + ".common.account.AccountPickerActivity", "block");
        rules.put(G + ".googlehelp.helpactivities.HelpActivity", "ok");
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".games.ui.SomethingActivity", rules));
        assertEquals(ScreenGuard.Verdict.BLOCK, ScreenGuard.classify(G, G + ".common.account.AccountPickerActivity", rules));
        assertEquals(ScreenGuard.Verdict.ALLOW, ScreenGuard.classify(G, G + ".googlehelp.helpactivities.HelpActivity", rules));
    }

    @Test
    public void shortNames() {
        assertEquals("accountsettings › AccountDetails", ScreenGuard.shortName(G + ".accountsettings.ui.AccountDetailsActivity"));
        assertEquals("Foo", ScreenGuard.shortName(G + ".FooActivity"));
    }
}
