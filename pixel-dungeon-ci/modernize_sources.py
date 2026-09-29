#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve()
JAVA = ROOT / "app" / "src" / "main" / "java"

def read(rel):
    p = JAVA / rel
    if not p.exists():
        raise SystemExit("Missing expected source: " + str(p))
    return p, p.read_text(encoding="utf-8")

def write(p, s):
    p.write_text(s, encoding="utf-8", newline="\n")

def replace_once(s, old, new, label):
    if old not in s:
        raise SystemExit("Patch anchor not found: " + label)
    return s.replace(old, new, 1)

def legacy_compiler_compat():
    for p in JAVA.rglob("*.java"):
        s = p.read_text(encoding="utf-8")
        n = (s
             .replace("FloatMath.cos(", "(float)Math.cos(")
             .replace("FloatMath.sin(", "(float)Math.sin(")
             .replace("FloatMath.sqrt(", "(float)Math.sqrt("))
        if n != s:
            write(p, n)

    fixes = {
        "com/watabou/pixeldungeon/items/Heap.java":
            [("(Collection<? extends Item>) bundle.getCollection( ITEMS )",
              "(Collection) bundle.getCollection( ITEMS )")],
        "com/watabou/pixeldungeon/actors/mobs/Mimic.java":
            [("(Collection<? extends Item>) bundle.getCollection( ITEMS )",
              "(Collection) bundle.getCollection( ITEMS )")],
        "com/watabou/pixeldungeon/levels/RegularLevel.java":
            [("(Collection<? extends Room>) bundle.getCollection( \"rooms\" )",
              "(Collection) bundle.getCollection( \"rooms\" )")],
    }
    for rel, replacements in fixes.items():
        p, s = read(rel)
        for old, new in replacements:
            s = replace_once(s, old, new, "compiler compatibility " + rel)
        write(p, s)

def patch_game():
    p, s = read("com/watabou/noosa/Game.java")
    s = replace_once(s,
        "import android.os.Bundle;\nimport android.os.Vibrator;",
        "import android.os.Build;\nimport android.os.Bundle;\nimport android.os.VibrationEffect;\nimport android.os.Vibrator;",
        "Game imports")
    s = replace_once(s,
        "import android.view.MotionEvent;\nimport android.view.SurfaceHolder;",
        "import android.view.MotionEvent;\nimport android.view.SurfaceHolder;\nimport android.window.OnBackInvokedDispatcher;",
        "Game back imports")
    s = replace_once(s,
        "\tpublic static float timeScale = 1f;\n\tpublic static float elapsed = 0f;",
        "\tpublic static float timeScale = 1f;\n\tpublic static float elapsed = 0f;\n\tpublic static boolean hapticsEnabled = true;\n\tpublic static boolean reducedMotion = false;",
        "Game accessibility state")
    s = replace_once(s,
        "\t\tDisplayMetrics m = new DisplayMetrics();\n\t\tgetWindowManager().getDefaultDisplay().getMetrics( m );\n\t\tdensity = m.density;",
        "\t\tDisplayMetrics m = getResources().getDisplayMetrics();\n\t\tdensity = m.density;",
        "Game display metrics")
    s = replace_once(s,
        "\t\tview.setRenderer( this );\n\t\tview.setOnTouchListener( this );\n\t\tsetContentView( view );",
        "\t\tview.setRenderer( this );\n\t\tview.setPreserveEGLContextOnPause( true );\n\t\tview.setOnTouchListener( this );\n\t\tsetContentView( view );\n\n\t\tif (Build.VERSION.SDK_INT >= 33) {\n\t\t\tgetOnBackInvokedDispatcher().registerOnBackInvokedCallback(\n\t\t\t\tOnBackInvokedDispatcher.PRIORITY_DEFAULT,\n\t\t\t\tthis::dispatchBack );\n\t\t}",
        "Game GLSurfaceView")
    key_block = "\t\tsynchronized (motionEvents) {\n\t\t\tkeysEvents.add( event );\n\t\t}"
    if s.count(key_block) != 2:
        raise SystemExit("Expected two legacy key lock blocks")
    s = s.replace(key_block,
        "\t\tsynchronized (keysEvents) {\n\t\t\tkeysEvents.add( event );\n\t\t}")
    s = replace_once(s,
        "\t\t\t\trequestedScene = sceneClass.newInstance();",
        "\t\t\t\trequestedScene = sceneClass.getDeclaredConstructor().newInstance();",
        "Game reflection")
    s = replace_once(s,
        "\tpublic static void vibrate( int milliseconds ) {\n\t\t((Vibrator)instance.getSystemService( VIBRATOR_SERVICE )).vibrate( milliseconds );\n\t}\n}",
        "\tprivate void dispatchBack() {\n\t\tKeyEvent down = new KeyEvent( KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK );\n\t\tKeyEvent up = new KeyEvent( KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK );\n\t\tsynchronized (keysEvents) {\n\t\t\tkeysEvents.add( down );\n\t\t\tkeysEvents.add( up );\n\t\t}\n\t}\n\n\t@Override\n\t@SuppressWarnings(\"deprecation\")\n\tpublic void onBackPressed() {\n\t\tif (Build.VERSION.SDK_INT < 33) {\n\t\t\tdispatchBack();\n\t\t} else {\n\t\t\tsuper.onBackPressed();\n\t\t}\n\t}\n\n\tpublic static void vibrate( int milliseconds ) {\n\t\tif (!hapticsEnabled || instance == null) return;\n\t\tVibrator vibrator = (Vibrator)instance.getSystemService( VIBRATOR_SERVICE );\n\t\tif (vibrator == null || !vibrator.hasVibrator()) return;\n\t\tvibrator.vibrate( VibrationEffect.createOneShot( milliseconds, VibrationEffect.DEFAULT_AMPLITUDE ) );\n\t}\n}",
        "Game back and haptics")
    write(p, s)

def patch_camera():
    p, s = read("com/watabou/noosa/Camera.java")
    s = replace_once(s,
        "\tpublic void shake( float magnitude, float duration ) {\n\t\tshakeMagX = shakeMagY = magnitude;",
        "\tpublic void shake( float magnitude, float duration ) {\n\t\tif (Game.reducedMotion) return;\n\t\tshakeMagX = shakeMagY = magnitude;",
        "Camera reduced motion")
    write(p, s)

def patch_touchscreen():
    p, s = read("com/watabou/input/Touchscreen.java")
    s = replace_once(s,
        "\t\t\tcase MotionEvent.ACTION_MOVE:\n\t\t\t\tint count = e.getPointerCount();\n\t\t\t\tfor (int j=0; j < count; j++) {\t\t\n\t\t\t\t\tpointers.get( e.getPointerId( j ) ).update( e, j );\n\t\t\t\t}\n\t\t\t\tevent.dispatch( null );\n\t\t\t\tbreak;",
        "\t\t\tcase MotionEvent.ACTION_MOVE:\n\t\t\t\tint count = e.getPointerCount();\n\t\t\t\tfor (int j=0; j < count; j++) {\n\t\t\t\t\tTouch active = pointers.get( e.getPointerId( j ) );\n\t\t\t\t\tif (active != null) active.update( e, j );\n\t\t\t\t}\n\t\t\t\tevent.dispatch( null );\n\t\t\t\tbreak;",
        "Touch MOVE safety")
    s = replace_once(s,
        "\t\t\tcase MotionEvent.ACTION_POINTER_UP:\n\t\t\t\tevent.dispatch( pointers.remove( e.getPointerId( e.getActionIndex() ) ).up() );\n\t\t\t\tbreak;\n\t\t\t\t\n\t\t\tcase MotionEvent.ACTION_UP:\n\t\t\t\ttouched = false;\n\t\t\t\tevent.dispatch( pointers.remove( e.getPointerId( 0 ) ).up() );\n\t\t\t\tbreak;",
        "\t\t\tcase MotionEvent.ACTION_POINTER_UP:\n\t\t\t\ttouch = pointers.remove( e.getPointerId( e.getActionIndex() ) );\n\t\t\t\tif (touch != null) event.dispatch( touch.up() );\n\t\t\t\tbreak;\n\t\t\t\t\n\t\t\tcase MotionEvent.ACTION_UP:\n\t\t\t\ttouched = false;\n\t\t\t\ttouch = pointers.remove( e.getPointerId( 0 ) );\n\t\t\t\tif (touch != null) event.dispatch( touch.up() );\n\t\t\t\tbreak;\n\n\t\t\tcase MotionEvent.ACTION_CANCEL:\n\t\t\t\ttouched = false;\n\t\t\t\tfor (Touch active : pointers.values()) event.dispatch( active.up() );\n\t\t\t\tpointers.clear();\n\t\t\t\tbreak;",
        "Touch UP/CANCEL safety")
    write(p, s)

def patch_button():
    p, s = read("com/watabou/noosa/ui/Button.java")
    s = replace_once(s, "public static float longClick = 1f;", "public static float longClick = 0.65f;", "long press delay")
    s = s.replace("Game.vibrate( 50 );", "Game.vibrate( 35 );")
    write(p, s)

def patch_preferences():
    p, s = read("com/watabou/pixeldungeon/Preferences.java")
    s = replace_once(s,
        "\tpublic static final String KEY_BRIGHTNESS\t= \"brightness\";",
        "\tpublic static final String KEY_BRIGHTNESS\t= \"brightness\";\n\tpublic static final String KEY_VIBRATION\t= \"vibration\";\n\tpublic static final String KEY_REDUCED_MOTION\t= \"reduced_motion\";",
        "accessibility preference keys")
    s = s.replace(".commit();", ".apply();")
    write(p, s)

def patch_bundle():
    p, s = read("com/watabou/utils/Bundle.java")
    s = replace_once(s,
        "import java.io.OutputStreamWriter;\nimport java.util.ArrayList;",
        "import java.io.OutputStreamWriter;\nimport java.nio.charset.StandardCharsets;\nimport java.util.ArrayList;",
        "Bundle charset import")
    s = s.replace("(Bundlable)cl.newInstance()", "(Bundlable)cl.getDeclaredConstructor().newInstance()")
    s = replace_once(s, "new InputStreamReader( stream )",
                     "new InputStreamReader( stream, StandardCharsets.UTF_8 )", "Bundle input charset")
    s = replace_once(s, "new String( bytes )",
                     "new String( bytes, StandardCharsets.UTF_8 )", "Bundle bytes charset")
    s = replace_once(s, "new OutputStreamWriter( stream )",
                     "new OutputStreamWriter( stream, StandardCharsets.UTF_8 )", "Bundle output charset")
    s = replace_once(s, "\t\t\twriter.close();", "\t\t\twriter.flush();", "Bundle stream ownership")
    write(p, s)

def patch_music():
    p, s = read("com/watabou/noosa/audio/Music.java")
    s = replace_once(s,
        "\t\t} catch (IOException e) {\n\t\t\t\n\t\t\tplayer.release();\n\t\t\tplayer = null;\n\t\t\t\n\t\t}",
        "\t\t} catch (IOException e) {\n\t\t\tif (player != null) {\n\t\t\t\tplayer.release();\n\t\t\t\tplayer = null;\n\t\t\t}\n\t\t}",
        "Music IOException")
    write(p, s)

def patch_sample():
    p, s = read("com/watabou/noosa/audio/Sample.java")
    s = replace_once(s,
        "import android.media.AudioManager;\nimport android.media.SoundPool;",
        "import android.media.AudioAttributes;\nimport android.media.AudioManager;\nimport android.media.SoundPool;",
        "Sample imports")
    s = replace_once(s,
        "\tprotected SoundPool pool = \n\t\tnew SoundPool( MAX_STREAMS, AudioManager.STREAM_MUSIC, 0 );",
        "\tprotected SoundPool pool = newPool();\n\n\tprivate static SoundPool newPool() {\n\t\tAudioAttributes attributes = new AudioAttributes.Builder()\n\t\t\t.setUsage( AudioAttributes.USAGE_GAME )\n\t\t\t.setContentType( AudioAttributes.CONTENT_TYPE_SONIFICATION )\n\t\t\t.build();\n\t\treturn new SoundPool.Builder()\n\t\t\t.setMaxStreams( MAX_STREAMS )\n\t\t\t.setAudioAttributes( attributes )\n\t\t\t.build();\n\t}",
        "SoundPool builder")
    s = replace_once(s,
        "\t\tpool = new SoundPool( MAX_STREAMS, AudioManager.STREAM_MUSIC, 0 );",
        "\t\tpool = newPool();",
        "SoundPool reset")
    s = s.replace("import android.media.AudioManager;\n", "")
    write(p, s)

def patch_pixel_dungeon():
    p, s = read("com/watabou/pixeldungeon/PixelDungeon.java")
    s = replace_once(s,
        "import android.content.pm.ActivityInfo;\nimport android.os.Bundle;\nimport android.util.DisplayMetrics;\nimport android.util.Log;\nimport android.view.View;",
        "import android.content.pm.ActivityInfo;\nimport android.content.res.Configuration;\nimport android.os.Build;\nimport android.os.Bundle;\nimport android.util.Log;\nimport android.view.View;\nimport android.view.Window;\nimport android.view.WindowInsets;\nimport android.view.WindowInsetsController;",
        "PixelDungeon imports")
    s = replace_once(s,
        "\t\tDisplayMetrics metrics = new DisplayMetrics();\n\t\tinstance.getWindowManager().getDefaultDisplay().getMetrics( metrics );\n\t\tboolean landscape = metrics.widthPixels > metrics.heightPixels;",
        "\t\tboolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;",
        "PixelDungeon orientation")
    s = replace_once(s,
        "\t\tMusic.INSTANCE.enable( music() );\n\t\tSample.INSTANCE.enable( soundFx() );",
        "\t\tMusic.INSTANCE.enable( music() );\n\t\tSample.INSTANCE.enable( soundFx() );\n\t\tGame.hapticsEnabled = vibration();\n\t\tGame.reducedMotion = reducedMotion();",
        "PixelDungeon accessibility init")
    old = """\t@SuppressLint("NewApi")
\tpublic static void updateImmersiveMode() {
\t\tif (android.os.Build.VERSION.SDK_INT >= 19) {
\t\t\ttry {
\t\t\t\t// Sometime NullPointerException happens here
\t\t\t\tinstance.getWindow().getDecorView().setSystemUiVisibility( 
\t\t\t\t\timmersed() ?
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_STABLE | 
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | 
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | 
\t\t\t\t\tView.SYSTEM_UI_FLAG_HIDE_NAVIGATION | 
\t\t\t\t\tView.SYSTEM_UI_FLAG_FULLSCREEN | 
\t\t\t\t\tView.SYSTEM_UI_FLAG_IMMERSIVE_STICKY 
\t\t\t\t\t:
\t\t\t\t\t0 );
\t\t\t} catch (Exception e) {
\t\t\t\treportException( e );
\t\t\t}
\t\t}
\t}"""
    new = """\t@SuppressLint("NewApi")
\tpublic static void updateImmersiveMode() {
\t\tif (instance == null || instance.getWindow() == null) return;
\t\tWindow window = instance.getWindow();
\t\tif (Build.VERSION.SDK_INT >= 30) {
\t\t\twindow.setDecorFitsSystemWindows( !immersed() );
\t\t\tWindowInsetsController controller = window.getInsetsController();
\t\t\tif (controller != null) {
\t\t\t\tif (immersed()) {
\t\t\t\t\tcontroller.hide( WindowInsets.Type.systemBars() );
\t\t\t\t\tcontroller.setSystemBarsBehavior(
\t\t\t\t\t\tWindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE );
\t\t\t\t} else {
\t\t\t\t\tcontroller.show( WindowInsets.Type.systemBars() );
\t\t\t\t}
\t\t\t}
\t\t} else {
\t\t\twindow.getDecorView().setSystemUiVisibility(
\t\t\t\timmersed() ?
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_STABLE |
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
\t\t\t\t\tView.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
\t\t\t\t\tView.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
\t\t\t\t\tView.SYSTEM_UI_FLAG_FULLSCREEN |
\t\t\t\t\tView.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0 );
\t\t}
\t}"""
    s = replace_once(s, old, new, "modern immersive mode")
    s = replace_once(s,
        "\tpublic static boolean soundFx() {\n\t\treturn Preferences.INSTANCE.getBoolean( Preferences.KEY_SOUND_FX, true );\n\t}",
        "\tpublic static boolean soundFx() {\n\t\treturn Preferences.INSTANCE.getBoolean( Preferences.KEY_SOUND_FX, true );\n\t}\n\n\tpublic static void vibration( boolean value ) {\n\t\tGame.hapticsEnabled = value;\n\t\tPreferences.INSTANCE.put( Preferences.KEY_VIBRATION, value );\n\t}\n\n\tpublic static boolean vibration() {\n\t\treturn Preferences.INSTANCE.getBoolean( Preferences.KEY_VIBRATION, true );\n\t}\n\n\tpublic static void reducedMotion( boolean value ) {\n\t\tGame.reducedMotion = value;\n\t\tPreferences.INSTANCE.put( Preferences.KEY_REDUCED_MOTION, value );\n\t}\n\n\tpublic static boolean reducedMotion() {\n\t\treturn Preferences.INSTANCE.getBoolean( Preferences.KEY_REDUCED_MOTION, false );\n\t}",
        "accessibility preference API")
    write(p, s)

def patch_dungeon_save():
    p, s = read("com/watabou/pixeldungeon/Dungeon.java")
    s = replace_once(s, "import com.watabou.pixeldungeon.items.Ankh;",
                     "import com.watabou.pixeldungeon.io.SaveFiles;\nimport com.watabou.pixeldungeon.items.Ankh;",
                     "SaveFiles import")
    s = replace_once(s,
        "\t\t\tOutputStream output = Game.instance.openFileOutput( fileName, Game.MODE_PRIVATE );\n\t\t\tBundle.write( bundle, output );\n\t\t\toutput.close();\n\t\t\t\n\t\t} catch (Exception e) {\n\n\t\t\tGamesInProgress.setUnknown( hero.heroClass );\n\t\t}\n\t}",
        "\t\t\tSaveFiles.writeSafely( fileName, bundle );\n\t\t\t\n\t\t} catch (Exception e) {\n\t\t\tGamesInProgress.setUnknown( hero.heroClass );\n\t\t\tif (e instanceof IOException) throw (IOException)e;\n\t\t\tthrow new IOException( \"Unable to save game\", e );\n\t\t}\n\t}",
        "safe game save")
    s = replace_once(s,
        "\t\tOutputStream output = Game.instance.openFileOutput( Utils.format( depthFile( hero.heroClass ), depth ), Game.MODE_PRIVATE );\n\t\tBundle.write( bundle, output );\n\t\toutput.close();",
        "\t\tSaveFiles.writeSafely( Utils.format( depthFile( hero.heroClass ), depth ), bundle );",
        "safe level save")
    s = replace_once(s,
        "\t\tInputStream input = Game.instance.openFileInput( Utils.format( depthFile( cl ), depth ) ) ;",
        "\t\tInputStream input = SaveFiles.openForRead( Utils.format( depthFile( cl ), depth ) );",
        "level recovery read")
    s = replace_once(s,
        "\t\tInputStream input = Game.instance.openFileInput( fileName );",
        "\t\tInputStream input = SaveFiles.openForRead( fileName );",
        "game recovery read")
    write(p, s)

def patch_settings():
    p, s = read("com/watabou/pixeldungeon/windows/WndSettings.java")
    s = replace_once(s,
        "import com.watabou.noosa.Camera;",
        "import com.watabou.noosa.Camera;\nimport com.watabou.noosa.Game;",
        "settings Game import")
    s = replace_once(s,
        "\tprivate static final String TXT_SOUND\t= \"Sound FX\";",
        "\tprivate static final String TXT_SOUND\t= \"Sound FX\";\n\tprivate static final String TXT_HAPTICS\t= \"Haptic feedback\";\n\tprivate static final String TXT_REDUCED_MOTION = \"Reduced motion\";",
        "settings accessibility labels")
    s = s.replace("private static final int WIDTH\t\t= 112;", "private static final int WIDTH\t\t= 124;")
    s = s.replace("private static final int BTN_HEIGHT\t= 20;", "private static final int BTN_HEIGHT\t= 22;")
    s = s.replace("private static final int GAP \t\t= 2;", "private static final int GAP \t\t= 3;")
    s = replace_once(s,
        "\t\tbtnSound.checked( PixelDungeon.soundFx() );\n\t\tadd( btnSound );",
        "\t\tbtnSound.checked( PixelDungeon.soundFx() );\n\t\tadd( btnSound );\n\n\t\tCheckBox btnHaptics = new CheckBox( TXT_HAPTICS ) {\n\t\t\t@Override\n\t\t\tprotected void onClick() {\n\t\t\t\tsuper.onClick();\n\t\t\t\tPixelDungeon.vibration( checked() );\n\t\t\t\tif (checked()) Game.vibrate( 30 );\n\t\t\t}\n\t\t};\n\t\tbtnHaptics.setRect( 0, btnSound.bottom() + GAP, WIDTH, BTN_HEIGHT );\n\t\tbtnHaptics.checked( PixelDungeon.vibration() );\n\t\tadd( btnHaptics );\n\n\t\tCheckBox btnReducedMotion = new CheckBox( TXT_REDUCED_MOTION ) {\n\t\t\t@Override\n\t\t\tprotected void onClick() {\n\t\t\t\tsuper.onClick();\n\t\t\t\tPixelDungeon.reducedMotion( checked() );\n\t\t\t}\n\t\t};\n\t\tbtnReducedMotion.setRect( 0, btnHaptics.bottom() + GAP, WIDTH, BTN_HEIGHT );\n\t\tbtnReducedMotion.checked( PixelDungeon.reducedMotion() );\n\t\tadd( btnReducedMotion );",
        "settings accessibility controls")
    s = replace_once(s,
        "btnBrightness.setRect( 0, btnSound.bottom() + GAP, WIDTH, BTN_HEIGHT );",
        "btnBrightness.setRect( 0, btnReducedMotion.bottom() + GAP, WIDTH, BTN_HEIGHT );",
        "settings brightness position")
    s = replace_once(s,
        "btnOrientation.setRect( 0, btnSound.bottom() + GAP, WIDTH, BTN_HEIGHT );",
        "btnOrientation.setRect( 0, btnReducedMotion.bottom() + GAP, WIDTH, BTN_HEIGHT );",
        "settings orientation position")
    write(p, s)

def patch_ui_polish():
    p, s = read("com/watabou/pixeldungeon/scenes/TitleScene.java")
    s = s.replace('private static final String TXT_BADGES\t\t= "Badges";',
                  'private static final String TXT_BADGES\t\t= "Achievements";')
    s = s.replace('private static final String TXT_ABOUT\t\t= "About";',
                  'private static final String TXT_ABOUT\t\t= "Credits";')
    s = s.replace("public static final float SIZE\t= 48;", "public static final float SIZE\t= 56;")
    s = s.replace("label = createText( 9 );", "label = createText( 7 );")
    write(p, s)

    p, s = read("com/watabou/pixeldungeon/windows/WndBag.java")
    s = s.replace("protected static final int SLOT_SIZE\t= 28;", "protected static final int SLOT_SIZE\t= 30;")
    s = s.replace("protected static final int SLOT_MARGIN\t= 1;", "protected static final int SLOT_MARGIN\t= 2;")
    s = s.replace("protected static final int TITLE_HEIGHT\t= 12;", "protected static final int TITLE_HEIGHT\t= 14;")
    write(p, s)

    p, s = read("com/watabou/pixeldungeon/ui/Window.java")
    s = s.replace("shadow.am = 0.5f;", "shadow.am = 0.65f;")
    write(p, s)

def patch_combat_feedback():
    p, s = read("com/watabou/pixeldungeon/actors/hero/Hero.java")
    s = replace_once(s,
        "\t\trestoreHealth = false;\n\t\tsuper.damage( dmg, src );",
        "\t\trestoreHealth = false;\n\t\tsuper.damage( dmg, src );\n\t\tif (dmg > 0 && Camera.main != null) {\n\t\t\tCamera.main.shake( Math.min( 2.5f, 0.8f + dmg * 0.08f ), 0.12f );\n\t\t}",
        "light hero hit shake")
    write(p, s)

def main():
    legacy_compiler_compat()
    patch_game()
    patch_camera()
    patch_touchscreen()
    patch_button()
    patch_preferences()
    patch_bundle()
    patch_music()
    patch_sample()
    patch_pixel_dungeon()
    patch_dungeon_save()
    patch_settings()
    patch_ui_polish()
    patch_combat_feedback()
    print("Pixel Dungeon modernization patches applied.")

if __name__ == "__main__":
    main()
