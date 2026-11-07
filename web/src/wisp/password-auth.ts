export const PASSWORD_AUTH_ID = 0x02;

const USERNAME_LENGTH_BYTES = 1;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** A client's message for Wisp's password extension. It's kept apart so it can be tested. */
export function encodeCredentials(username: string, password: string): Uint8Array {
  const name = encoder.encode(username);
  if (name.length > 0xff) throw new RangeError('That username is too long for Wisp.');
  const secret = encoder.encode(password);
  const message = new Uint8Array(USERNAME_LENGTH_BYTES + name.length + secret.length);
  message[0] = name.length;
  message.set(name, USERNAME_LENGTH_BYTES);
  message.set(secret, USERNAME_LENGTH_BYTES + name.length);
  return message;
}

export function decodeCredentials(message: Uint8Array): { username: string; password: string } {
  const nameEnd = USERNAME_LENGTH_BYTES + (message[0] ?? 0);
  return {
    username: decoder.decode(message.subarray(USERNAME_LENGTH_BYTES, nameEnd)),
    password: decoder.decode(message.subarray(nameEnd)),
  };
}

interface BufferLike {
  bytes: Uint8Array;
}

// A class that extends its argument has to take any arguments. That's TypeScript's rule for mixins.
type ExtensionClass = (new (...args: any[]) => object) & { Client: unknown };

/**
 * Wisp's password extension, as the final version of the protocol lays it out.
 *
 * wisp-js has this extension, but still writes the client's message the way an early draft did, with a
 * separate field for the password's length. Servers that follow the protocol, like epoxy-server, read that
 * as a wrong password. This keeps wisp-js's class and swaps the message. It takes wisp-js's classes as
 * arguments because the browser and the development server each import their own entry point.
 */
export function definePasswordAuth<Base extends ExtensionClass>(base: Base, WispBuffer: new (bytes: Uint8Array) => BufferLike) {
  class Client {
    username: string;
    password: string;

    constructor({ username, password }: { username: string; password: string }) {
      this.username = username;
      this.password = password;
    }

    static parse(buffer: BufferLike) {
      return new Client(decodeCredentials(buffer.bytes));
    }

    serialize() {
      return new WispBuffer(encodeCredentials(this.username, this.password));
    }
  }

  return class SpecPasswordAuth extends base {
    static override Client = Client;
  };
}
