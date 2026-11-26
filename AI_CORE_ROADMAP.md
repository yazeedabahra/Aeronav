# Yvora 1.3-dev — first AI integration stage

Implemented:
- Knife-hand pose gate based on the palm plane normal and extended, adjacent fingers in existing 3D hand landmarks. Two-sample hysteresis; motion uses existing palm-relative fuzzy swipe. The legacy three-finger motion test adapter is not used in runtime. Face-on open palm retains pointer control. No added camera stream or model.
- AI Core command-preview allowlist; accepts at most four exact navigation tokens. All other output rejected. No direct accessibility or gesture injection from remote responses.
- Model Lab screen with manual provider and model ID, one-shot text-only HTTPS REST adapters for OpenAI, Gemini, Claude, DeepSeek. User supplies a restricted personal test key per request; key field clears on submit, not saved or logged. No credentials embedded. No redirects, no automatic requests, no camera/screen data. Network request may be billed by provider. Android FLAG_SECURE on lab.
- This prototype is not a production credential architecture. For distribution, use a backend with authenticated access and managed credentials; do not ship production keys or encourage users to paste keys into chat.
- No cloud processing of live gestures. Thermal control and offline control remain unchanged.
- Not implemented yet: adaptive AI-assisted recognition, screen understanding, voice, autonomous action execution, command confirmation/safety policy for real actions, memory, secure backend and real provider testing. User must explicitly authorize screen sharing; agent execution should be bounded, visible, revocable, and subordinate to manual input.

Verification: synthetic orientation and allowlist unit tests added; actual camera recognition and provider integration require device testing with user credentials. Model IDs are deliberately user-configured since access and availability vary. Official provider docs:
https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
https://ai.google.dev/api/generate-content
https://platform.claude.com/docs/en/api/messages/create
https://api-docs.deepseek.com/api/create-chat-completion/
