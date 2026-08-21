package redis.utils;

import java.io.IOException;
import java.io.OutputStream;

import redis.resp.RespWriter;

public class CommandUtils {

    private CommandUtils() {} // prevent instantiation

    // -------------------------
    // ARITY CHECKS
    // -------------------------

    public static boolean arity(RespWriter writer, OutputStream out, String cmd, int actual, int expected) throws IOException {
        if (actual != expected) {
            writer.writeError(out, "wrong number of arguments for '" + cmd.toLowerCase() + "' command");
            out.flush();
            return false;
        }
        return true;
    }

    public static boolean minArity(RespWriter writer, OutputStream out, String cmd, int actual, int min) throws IOException {
        if (actual < min) {
            writer.writeError(out, "wrong number of arguments for '" + cmd.toLowerCase() + "' command");
            out.flush();
            return false;
        }
        return true;
    }

    // -------------------------
    // NUMBER PARSERS
    // -------------------------

    public static Integer parseIntArg(RespWriter writer, OutputStream out, String s) throws IOException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not an integer or out of range");
            out.flush();
            return null;
        }
    }

    public static Long parseLongArg(RespWriter writer, OutputStream out, String s) throws IOException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not an integer or out of range");
            out.flush();
            return null;
        }
    }

    public static Double parseDoubleArg(RespWriter writer, OutputStream out, String s) throws IOException {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            writer.writeError(out, "value is not a valid float");
            out.flush();
            return null;
        }
    }
}
