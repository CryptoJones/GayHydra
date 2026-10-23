/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.decompiler;

import java.io.File;
import java.util.Objects;

import ghidra.framework.*;
import ghidra.util.Msg;

/**
 * Factory that returns a DecompileProcess.
 * <p>
 * The executable the process is spawned from is normally the native {@code decompile}
 * shipped in this module's OS directory. It can be pointed elsewhere, without copying
 * a binary over the stock one or writing this class's fields by reflection, through
 * three seams consulted in this order on every {@link #get()}:
 * <ol>
 * <li>{@link #setExecutable(String)}, for a plugin swapping the core at runtime;</li>
 * <li>the system property {@value #EXECUTABLE_PROPERTY}, for a headless run
 * ({@code -Dghidra.decompiler.exe=/path/to/core});</li>
 * <li>the environment variable {@value #EXECUTABLE_ENV}.</li>
 * </ol>
 * A property or variable that does not name an executable file is reported once and
 * ignored, so a typo degrades to the stock decompiler rather than to no decompiler.
 * The stock lookup is still cached; the seams are re-read each time, so clearing the
 * property or calling {@link #resetExecutable()} takes effect on the next process.
 */
public class DecompileProcessFactory {

	/** System property naming the decompiler executable to spawn instead of the stock one. */
	public static final String EXECUTABLE_PROPERTY = "ghidra.decompiler.exe";
	/** Environment variable naming the decompiler executable to spawn instead of the stock one. */
	public static final String EXECUTABLE_ENV = "GHIDRA_DECOMPILER_EXE";

	private static String exepath;
	private static final String EXECNAME = "decompile";
	private static final String WIN32_EXECNAME = "decompile.exe";

	private static String overridePath;
	private static String announcedPath;
	private static String rejectedSetting;

	private static boolean errorDisplayed = false;
	
	private static synchronized boolean getAndSetErrorDisplayed() {
		boolean b = errorDisplayed;
		if (!b) {
			errorDisplayed = true;
		}
		return b;
	}

	public synchronized static DecompileProcess get() {
		DecompileProcess currentProcess = new DecompileProcess(getExecutablePath());
		return currentProcess;
	}

	public synchronized static void release(DecompileProcess dp) {
		dp.dispose();
	}

	/**
	 * Returns the path {@link #get()} would spawn right now: the runtime override, the
	 * system property, the environment variable, or the stock executable, whichever is
	 * set first. The stock path is {@code null} when the native decompiler is missing.
	 * 
	 * @return the path of the decompiler executable, or {@code null} when none is found
	 */
	public synchronized static String getExecutablePath() {
		String path = resolve(overridePath, System.getProperty(EXECUTABLE_PROPERTY),
			System.getenv(EXECUTABLE_ENV));
		if (path != null) {
			return path;
		}
		getExePath();
		return exepath;
	}

	/**
	 * Makes every later {@link #get()} spawn the given executable instead of the stock
	 * one, ahead of the system property and the environment variable. Processes already
	 * running are not affected.
	 * 
	 * @param path absolute path of an executable file
	 * @throws IllegalArgumentException if the path is not an executable file
	 */
	public synchronized static void setExecutable(String path) {
		Objects.requireNonNull(path, "path");
		File file = new File(path);
		if (!file.isFile() || !file.canExecute()) {
			throw new IllegalArgumentException(
				"Decompiler executable is not an executable file: " + path);
		}
		overridePath = file.getAbsolutePath();
	}

	/**
	 * Drops the executable set with {@link #setExecutable(String)}. The next
	 * {@link #get()} goes back to the system property, the environment variable or the
	 * stock executable.
	 */
	public synchronized static void resetExecutable() {
		overridePath = null;
	}

	/**
	 * Picks the executable from the three seams, in order. Package-private so the
	 * resolution order can be tested without an initialized Application and without
	 * setting a real environment variable.
	 * 
	 * @param override the path set at runtime, or {@code null}
	 * @param property the value of {@value #EXECUTABLE_PROPERTY}, or {@code null}
	 * @param env the value of {@value #EXECUTABLE_ENV}, or {@code null}
	 * @return the first usable path, or {@code null} to use the stock executable
	 */
	static synchronized String resolve(String override, String property, String env) {
		if (override != null) {
			return override;
		}
		String path = usable(property, "system property " + EXECUTABLE_PROPERTY);
		if (path != null) {
			return path;
		}
		return usable(env, "environment variable " + EXECUTABLE_ENV);
	}

	private static String usable(String setting, String source) {
		if (setting == null || setting.isBlank()) {
			return null;
		}
		File file = new File(setting.trim());
		if (file.isFile() && file.canExecute()) {
			String path = file.getAbsolutePath();
			if (!path.equals(announcedPath)) {
				announcedPath = path;
				Msg.info(DecompileProcessFactory.class,
					"Decompiler executable taken from " + source + ": " + path);
			}
			return path;
		}
		if (!setting.equals(rejectedSetting)) {
			rejectedSetting = setting;
			Msg.showError(DecompileProcessFactory.class, null, "Decompiler Not Found",
				"The " + source + " does not name an executable file, " +
					"so the stock decompiler is used instead: " + setting);
		}
		return null;
	}

	private static void getExePath() {
		if (exepath != null) {
			return;
		}

		String exeName;
		if (Platform.CURRENT_PLATFORM.getOperatingSystem() == OperatingSystem.WINDOWS) {
			exeName = WIN32_EXECNAME;
		}
		else {
			exeName = EXECNAME;
		}

		try {
			File file = Application.getOSFile(exeName);
			exepath = file.getAbsolutePath();
		}
		catch (OSFileNotFoundException e) {
			if (!getAndSetErrorDisplayed()) {
				Msg.showError(DecompileProcessFactory.class, null, "Decompiler Not Found",
					e.getMessage());
			}
		}
	}
}
