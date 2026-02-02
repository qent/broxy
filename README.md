# Broxy — your single MCP endpoint

Broxy is the one local endpoint that unifies all your MCP tools and clients. Instead of wiring
every agent to every server, you connect everything to Broxy once and keep your setup clean,
focused, and easy to switch per task.

## Why Broxy

- Keep agents focused on the job: expose only the tools that matter.
- Save money and context tokens: fewer tools, fewer irrelevant descriptions.
- One endpoint for every client: Claude Desktop, IDEs, and custom agents all connect the same way.
- Fast context switching: distinct tool sets for work, personal tasks, or team flows.
- Less setup overhead: one local proxy instead of many integrations.
- More control and safety: you choose exactly what each client can access.
- Local-first on your Mac: config and keys stay on your machine.

## How it works

1. Add MCP servers in Broxy.
2. Create task-focused tool sets.
3. Point clients to Broxy and work through one endpoint.

## Connection examples

### STDIO (local connection, for example Claude Desktop)

Pick your tool set in Broxy, then connect via STDIO:

```json
{
  "mcpServers": {
    "broxy": {
      "command": "/Applications/broxy.app/Contents/MacOS/broxy",
      "args": ["--stdio-proxy"]
    }
  }
}
```

### Streamable HTTP (local HTTP endpoint)

Start Broxy and connect over a local HTTP endpoint:

```json
{
  "mcpServers": {
    "broxy": {
      "url": "http://localhost:3335/mcp"
    }
  }
}
```

Need a different port? Change it in the app settings.
