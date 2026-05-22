package bezier.run;

import java.awt.Font;
import java.awt.Toolkit;

import javax.swing.UIDefaults;
import javax.swing.UIManager;

public final class UIInitializer
{
	public static final double SCALE = Toolkit.getDefaultToolkit().getScreenResolution() / 96.0;

	private static boolean initialized = false;

	public static void init()
	{
		if (initialized) return;
		initialized = true;

		UIDefaults defaults = UIManager.getDefaults();

		for (Object key : defaults.keySet()) {
			Object value = defaults.get(key);
			if (value instanceof Font) {
				Font font = (Font) value;
				float newSize = (float)(font.getSize2D() * SCALE);
				defaults.put(key, font.deriveFont(newSize));
			}
		}
	}
}