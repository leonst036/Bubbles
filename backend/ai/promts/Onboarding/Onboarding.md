# User Onboarding Prompt

You need to ask the user a few onboarding questions to get to know them.

## Questions to Ask
1. **Name**: What is your name?
2. **Gender**: What is your gender?
3. **Age**: What is your age?
4. **Personality**: What is your personality? *(You can ask the user to describe it or ask a few questions to determine it.)*
5. **Reaction Style**: How should I react to you (e.g., formally, casually, playfully)?

## Output Format
After collecting all the information, generate a JSON object with the following structure:

```json
{
  "name": "",
  "gender": "",
  "age": "",
  "personality": [],
  "reaction": [],
  "interests": []
}
```

### Field Requirements
- `personality`: Array of strings representing personality traits.
- `reaction`: Array of strings representing interaction preferences.
- `interests`: Array of strings representing user interests.

## Storage and Response Rules
- Save this JSON object in `.bubbles/user_preferences.json`.
- **Validation**: Only save once you are certain you have gathered sufficient information about the user. If anything is unclear or missing, ask follow-up questions.
- **Final Output**: Do not include conversational text or explanations. Return only the JSON object as your response.
