// Only what Shira uses. wisp-js ships no types of its own.

declare module '@mercuryworkshop/wisp-js/client' {
  export { client, extensions, packet };

  namespace packet {
    class WispBuffer {
      constructor(source: number | string | Uint8Array);
      bytes: Uint8Array;
      size: number;
      view: DataView;
      concat(other: WispBuffer): WispBuffer;
      slice(start: number, length?: number): WispBuffer;
      get_string(): string;
    }
  }

  namespace extensions {
    class BaseExtension {
      static id: number;
      static name: string;
      static Server: unknown;
      static Client: unknown;
      id: number;
      payload: unknown;
      constructor(options?: { server_config?: unknown; client_config?: unknown });
      serialize(): packet.WispBuffer;
    }
    class UDPExtension extends BaseExtension {}
    class MOTDExtension extends BaseExtension {}
    class PasswordAuthExtension extends BaseExtension {
    }
  }

  namespace client {
    class ClientStream {
      hostname: string;
      port: number;
      stream_id: number;
      buffer_size: number;
      send_buffer: Uint8Array[];
      open: boolean;
      onopen: () => void;
      onclose: (reason: number | null) => void;
      onmessage: (data: Uint8Array) => void;
      send(data: Uint8Array): void;
      close(reason?: number): void;
      continue_received(buffer_size: number): void;
    }

    interface WispSocket {
      binaryType: string;
      bufferedAmount: number;
      onmessage: ((event: { data: ArrayBufferLike | Uint8Array }) => void) | null;
      onclose: (() => void) | null;
      onerror: (() => void) | null;
      send(data: Uint8Array): void;
      close(): void;
    }

    class ClientConnection {
      constructor(url: string, options?: { wisp_version?: number; wisp_extensions?: extensions.BaseExtension[] | null });
      ws: WispSocket;
      wisp_url: string;
      wisp_version: number;
      wisp_extensions: extensions.BaseExtension[] | null;
      max_buffer_size: number | null;
      connected: boolean;
      connecting: boolean;
      udp_enabled: boolean;
      server_motd: string | null | undefined;
      onopen: () => void;
      onclose: () => void;
      onerror: () => void;
      connect_ws(): void;
      add_extensions(): void;
      cleanup(): void;
      on_ws_msg(event: { data: ArrayBufferLike | Uint8Array }): void;
      close(): void;
      create_stream(hostname: string, port: number, type?: number | string): ClientStream;
    }
  }
}

declare module '@mercuryworkshop/wisp-js/server' {
  import type { WebSocket } from 'ws';
  export { extensions, logging, server };

  namespace extensions {
    class BaseExtension {
      static id: number;
      static name: string;
      static Server: unknown;
      static Client: unknown;
      id: number;
      payload: unknown;
      constructor(options?: { server_config?: unknown; client_config?: unknown });
    }
    class UDPExtension extends BaseExtension {}
    class PasswordAuthExtension extends BaseExtension {}
  }

  namespace logging {
    const WARN: number;
    function set_level(level: number): void;
  }

  namespace server {
    const options: {
      allow_loopback_ips: boolean;
      allow_private_ips: boolean;
      [key: string]: unknown;
    };
    class ServerConnection {
      constructor(
        ws: WebSocket,
        path: string,
        options?: { wisp_version?: number; wisp_extensions?: extensions.BaseExtension[] },
      );
      client_exts: Record<number, extensions.BaseExtension | undefined>;
      setup(): Promise<void>;
      setup_wisp_v2(): Promise<void>;
      run(): Promise<void>;
      send_close_packet(stream: number, reason: number): Promise<void>;
    }
  }
}

declare const __SHIRA_VERSION__: string;
declare const __SHIRA_VERSION_CODE__: number;
