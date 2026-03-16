package bench.cli.ui;

import java.io.IOException;

public class Terminal {
    
    // ANSI Colors
    public static final String RESET = "\u001B[0m";
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // ANSI Backgrounds
    public static final String BG_BLACK = "\u001B[40m";
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_PURPLE = "\u001B[45m";
    public static final String BG_CYAN = "\u001B[46m";
    public static final String BG_WHITE = "\u001B[47m";

    // ANSI Styles
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String BLINK = "\u001B[5m";
    public static final String REVERSE = "\u001B[7m";
    public static final String HIDDEN = "\u001B[8m";

    // Cursor Movement
    public static final String HIDE_CURSOR = "\u001B[?25l";
    public static final String SHOW_CURSOR = "\u001B[?25h";
    public static final String CLEAR_SCREEN = "\u001B[2J\u001B[H";
    public static final String MOVE_UP = "\u001B[1A";
    public static final String ERASE_LINE = "\u001B[2K";

    // Alternate Screen Buffer (for full-screen apps like vim/htop)
    public static final String ENTER_ALT_SCREEN = "\u001B[?1049h";
    public static final String EXIT_ALT_SCREEN = "\u001B[?1049l";

    public static void enterAlternateScreen() {
        System.out.print(ENTER_ALT_SCREEN);
        System.out.flush();
    }

    public static void exitAlternateScreen() {
        System.out.print(EXIT_ALT_SCREEN);
        System.out.flush();
    }

    public static void clearScreen() {
        System.out.print(CLEAR_SCREEN);
        System.out.flush();
    }

    public static void hideCursor() {
        System.out.print(HIDE_CURSOR);
    }

    public static void showCursor() {
        System.out.print(SHOW_CURSOR);
    }

    public static void print(String text) {
        System.out.print(text);
    }

    public static void println(String text) {
        System.out.println(text);
    }

    public static void printHeader(String title) {
        clearScreen();
        println(BOLD + BLUE + "================================================================================" + RESET);
        println(BOLD + BLUE + "   " + title.toUpperCase() + RESET);
        println(BOLD + BLUE + "================================================================================" + RESET);
        println("");
    }

    public static void printBox(String title, String content) {
        println(BOLD + CYAN + "┌─ " + title + " " + "─".repeat(Math.max(0, 76 - title.length())) + RESET);
        for (String line : content.split("\n")) {
            println(BOLD + CYAN + "│ " + RESET + line);
        }
        println(BOLD + CYAN + "└" + "─".repeat(79) + RESET);
    }

    public static void printError(String message) {
        println(BOLD + RED + "ERROR: " + message + RESET);
    }

    public static void printSuccess(String message) {
        println(BOLD + GREEN + "SUCCESS: " + message + RESET);
    }

    public static void printWarning(String message) {
        println(BOLD + YELLOW + "WARNING: " + message + RESET);
    }

    public static String colorize(String text, String color) {
        return color + text + RESET;
    }
}
