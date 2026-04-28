# Project Instructions

## Intent And Parser Rules

1. Do not use regexes, string matching hard-code, or other local heuristic patches to fix semantic interpretation bugs.
2. For parser semantic errors, prefer fixing the parser prompt, output schema, or upstream representation. Do not add local fallback normalization logic in app code or debugger code as a semantic band-aid.
3. Always design parser and screen analyzer behavior with future multilingual support in mind. Even if the current UI is Chinese-first, implementation must not assume users only speak or input Chinese, and must remain extensible to 20+ languages.
4. Always preserve generalizability across apps and domains. A fix must target the semantic class of behavior rather than a single named app, vendor, or screen. Do not ship a fix that only works for one app-specific phrasing if the same intent should work for other apps and platforms.

## Engineering Expectations

1. When fixing intent parsing bugs, validate the result through the parser path itself instead of masking the symptom in downstream rendering or post-processing.
2. Prefer solutions that improve cross-language and cross-app behavior together. If a change only helps one Chinese phrasing or one app family, treat it as incomplete.
3. Never commit changes without actual testing. If the relevant verification has not been run successfully, do not create a commit.
4. If Android verification or build tooling updates `app/version.properties` as part of the checked workflow, treat that version bump as part of the change and include it in the commit unless the user explicitly says otherwise.
5. Unless the user explicitly asks for a feature branch or says not to, commit directly to `main` in this repo.
