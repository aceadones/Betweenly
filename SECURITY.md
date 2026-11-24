# Security notes

Threat model: a curious relay operator, a network attacker, someone who finds the relay URL. Not in scope: a compromised phone.

- Relay ids are 128-bit secrets known only to the two phones; they act as capabilities.
- Known limits (V1): the relay can see traffic timing and message sizes (padded to 512 B), your IP, and your FCM token. A stolen unlocked phone exposes everything on it. There is no forward secrecy yet (a long-term key per pair; ratcheting is planned for V2 chat).
- Report issues privately to the repo owner.
