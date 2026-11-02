package xyz.vvzvlad.projectorremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Stateless MCP server over Streamable HTTP with plain JSON responses (no SSE, no session id).
 * https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
 */
final class Mcp {
    private static final String LATEST_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_VERSIONS =
            Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05");

    private static final String STATUS_DESCRIPTION =
            "Read the projector state: power (On/Off), current input and lamp hours. Takes about a second.";

    private static final String POWER_DESCRIPTION =
            "Turn the Promethean PRM-35 projector on or off. \"on\" sends ~PN and works only from "
                    + "standby: right after \"off\" (~PF) the lamp has to cool down before it can be turned on again.";

    private static final String COMMAND_DESCRIPTION =
            "Send one raw command to the Promethean PRM-35 projector and return its reply. "
                    + "A command starts with '~'; an unknown command replies 'N'.\n"
                    + "Power: ~PN on (only from standby, wait for cooldown after off), ~PF off.\n"
                    + "Inputs: ~SR RGB (VGA), ~SG RGB2, ~SV Video, ~SS S-Video, ~SH HDMI. ~AI auto image.\n"
                    + "Queries: ~qP power (On/Off), ~qS input, ~qL lamp hours, ~qV version, ~qB brightness, "
                    + "~qC contrast, ~qA aspect, ~qT color temperature, ~qJ projection mode, ~qR, ~qN.\n"
                    + "Settings: ~sB<0-100> brightness, ~sC<0-100> contrast, "
                    + "~sA<0-4> aspect (0 Fill, 1 4:3, 2 16:9, 3 Letterbox, 4 Native), "
                    + "~sT<0-2> color temperature, ~sJ<0-3> projection mode.\n"
                    + "Remote keys: ~rU ~rD ~rL ~rR arrows, ~rN enter, ~rM menu, ~rP power, ~rI input, "
                    + "~rA auto, ~rK / ~rJ keystone +/-, ~rS status, ~rZ / ~rY zoom +/-, "
                    + "~rB blank, ~rF freeze.";

    private Mcp() {}

    /**
     * Handles one JSON-RPC message. Returns the response, or null for a notification or a
     * client response (answered with HTTP 202). Throws JSONException if the body is not a JSON object.
     */
    static JSONObject handle(String body) throws JSONException {
        JSONObject request = new JSONObject(body);
        String method = request.optString("method", null);
        if (method == null || !request.has("id") || method.startsWith("notifications/")) return null;

        Object id = request.get("id");
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();

        switch (method) {
            case "initialize":
                return result(id, initialize(params));
            case "ping":
                return result(id, new JSONObject());
            case "tools/list":
                return result(id, new JSONObject().put("tools", tools()));
            case "tools/call":
                return callTool(id, params);
            default:
                return error(id, -32601, "Method not found: " + method);
        }
    }

    static JSONObject parseError(JSONException e) {
        try {
            return error(JSONObject.NULL, -32700, "Parse error: " + e.getMessage());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JSONObject initialize(JSONObject params) throws JSONException {
        String requested = params.optString("protocolVersion");
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;
        return new JSONObject()
                .put("protocolVersion", version)
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("serverInfo", new JSONObject().put("name", "projector-remote").put("version", "1.0"));
    }

    private static JSONArray tools() throws JSONException {
        JSONObject state = new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray().put("on").put("off"));
        JSONObject command = new JSONObject()
                .put("type", "string")
                .put("description", "Raw command with the leading tilde, e.g. ~qP or ~sB60");
        return new JSONArray()
                .put(tool("projector_status", STATUS_DESCRIPTION, new JSONObject()))
                .put(tool("projector_power", POWER_DESCRIPTION, new JSONObject().put("state", state), "state"))
                .put(tool("projector_command", COMMAND_DESCRIPTION, new JSONObject().put("command", command), "command"));
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required)
            throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) schema.put("required", new JSONArray(Arrays.asList(required)));
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private static JSONObject callTool(Object id, JSONObject params) throws JSONException {
        String name = params.optString("name");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            switch (name) {
                case "projector_status":
                    return result(id, toolResult(Projector.status().toString(), false));
                case "projector_power": {
                    String state = args.optString("state");
                    String command;
                    if (state.equals("on")) command = "~PN";
                    else if (state.equals("off")) command = "~PF";
                    else return result(id, toolResult("state must be \"on\" or \"off\"", true));
                    return result(id, toolResult(command + " -> " + Projector.send(command), false));
                }
                case "projector_command": {
                    String command = args.optString("command");
                    if (command.isEmpty()) return result(id, toolResult("command is required", true));
                    return result(id, toolResult(command + " -> " + Projector.send(command), false));
                }
                default:
                    return error(id, -32602, "Unknown tool: " + name);
            }
        } catch (IOException e) {
            return result(id, toolResult(e.getMessage(), true));
        }
    }

    private static JSONObject toolResult(String text, boolean isError) throws JSONException {
        JSONObject content = new JSONObject().put("type", "text").put("text", text);
        return new JSONObject().put("content", new JSONArray().put(content)).put("isError", isError);
    }

    private static JSONObject result(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }
}
