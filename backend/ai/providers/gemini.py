from google import genai

client = genai.Client()

stream = client.interactions.create(
    model="gemini-3.6-flash",
    input="Say hi to Bubless",
    stream=True
)
for event in stream:
    if hasattr(event, "delta") and hasattr(event.delta, "text"):
        print(event.delta.text, end="", flush=True)

print()