"""Generate the bundled demo conversation (src/main/resources/demo-conversation.json).

Every account, name and reply is fictional. The groups below are written to exercise each signal the lens looks
at, so the visualization has structure to reveal:

  organic        varied language, irregular timing, established accounts (some reply twice)
  future_stack   near-duplicate template variants in a burst seconds after the post, young accounts
  nextwave       a second template burst a few minutes later, with a mix of young and older accounts
  agent_daily    accounts that repeat their own stock reply three times across the thread
  builders_club  people coordinating an announcement: similar text and timing, old ordinary accounts
  signal_notes   generic filler posted far apart: repetitive but not coordinated
  ambiguous      accounts that don't fit cleanly (new but original, fast but distinct, echoes without a burst)

Run: python3 scripts/generate_demo.py
"""
import json
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

random.seed(2026)
BASE = datetime(2026, 10, 6, 10, 0, tzinfo=timezone.utc)
rows = []
accounts = {}


def account(username, age, followers, following, posts):
    if username not in accounts:
        accounts[username] = {"id": f"acct_{len(accounts) + 1:03d}", "username": username, "accountAgeDays": age,
                              "followers": followers, "following": following, "totalPosts": posts}
    return accounts[username]


def reply(acct, text, second):
    rows.append({"author": acct, "text": text, "createdAt": BASE + timedelta(seconds=round(second))})


def organic_meta():
    age = random.randint(500, 4200)
    return age, random.randint(90, 9500), random.randint(120, 1500), int(age * random.uniform(0.4, 7))


organic = [
    ("maya_codes", "The most useful change for me has been generating tests before I touch the implementation."),
    ("eli_dev", "We tried agents on our monorepo. Great at small isolated tasks, less convincing at architecture."),
    ("julesbuilds", "Does anyone have a good way to review the changes when a task touches forty files?"),
    ("sara_systems", "I still spend more time understanding the problem than writing the actual code."),
    ("noahcraft", "Our junior engineers are learning faster when they ask the tools to explain their reasoning."),
    ("ren_design", "The missing piece is taste. Knowing what should exist is harder than producing it."),
    ("pixelriver", "I made a little garden simulator this weekend with an agent. Never shipped a game before."),
    ("tess_runtime", "What happens when two agents modify shared state at the same time? Honest question."),
    ("adam_ports", "The economics will change, but debugging distributed systems is still debugging distributed systems."),
    ("olive_branch", "For accessibility audits the tools already save us several hours each week."),
    ("leo_console", "A teammate caught a subtle race condition the model had confidently introduced yesterday."),
    ("nina_notebook", "Curious whether the maintenance cost goes up after the first six months."),
    ("byteandtea", "I love the optimism here. Would like to see a reproducible benchmark on legacy systems."),
    ("kai_studio", "Small teams can explore so many more prototypes now. That part feels genuinely different."),
    ("ava_query", "SQL generation works surprisingly well when the schema documentation is decent."),
    ("finn_loop", "We need better interfaces for handing work back and forth between people and software."),
    ("lena_reads", "There is a difference between a convincing demo and a reliable everyday workflow."),
    ("sam_stack", "My side project finally has documentation. That alone has been worth the experiment."),
    ("ivy_circuit", "Would recommend trying this on a codebase you know well before trusting it on an unfamiliar one."),
    ("theo_async", "Latency is still the biggest issue for interactive programming sessions."),
    ("milo_draws", "As a designer I can finally test interactions without waiting for someone else to implement them."),
    ("zoe_shell", "Anyone else find themselves writing more precise specifications now?"),
    ("owen_frame", "The conversation about responsibility is going to be as interesting as the technical progress."),
    ("arturo_lab", "Our build pipeline is the bottleneck. Faster code generation just makes the queue longer."),
    ("clara_notes", "Teaching students to question generated answers should be part of the curriculum."),
    ("devon_labs", "I want the boring migration work automated so I can focus on the difficult product questions."),
    ("bea_cloud", "We evaluated three approaches last month. Context management mattered more than model choice."),
    ("max_packet", "Network permissions for autonomous tools deserve a careful design review."),
    ("sienna_io", "The strongest results came when a person decomposed the work into small verifiable pieces."),
    ("haru_makes", "Built a recipe organizer for my family. Watching them use it was the best part."),
    ("lucas_merge", "Pull requests are becoming the unit of conversation between developers and agents."),
    ("drew_types", "Types help a lot here. The compiler provides feedback that natural language cannot."),
    ("iris_field", "I wonder how open source maintainers will cope with the volume of generated contributions."),
    ("robin_web", "The tools struggle with ambiguous requirements in exactly the places our team struggles."),
]
follow_ups = {
    "tess_runtime": "Answering myself: locks around the shared files helped, but we still needed a human merge step.",
    "nina_notebook": "@byteandtea agreed. A public benchmark on a ten year old codebase would settle a lot of arguments.",
    "julesbuilds": "Thanks for the suggestions. Splitting the task into reviewable commits made it manageable.",
    "ren_design": "To be clear, I'm excited about it. Taste just becomes the scarce skill.",
}
t = 0.0
for name, text in organic:
    t += random.uniform(25, 75)
    acct = account(name, *organic_meta())
    reply(acct, text, t)
for name, text in follow_ups.items():
    reply(accounts[name], text, random.uniform(900, 2500))

# A burst of near-duplicates seconds after the post: young accounts with skewed follow ratios.
openers = ["The future is already here.", "The future is here already.", "The future is already here!"]
middles = ["AI agents will change everything.", "AI agents are going to change everything.",
           "AI agents will change absolutely everything."]
closers = ["Adapt now or get left behind.", "Adapt now or be left behind.", "Adapt or get left behind.",
           "Adapt now or get left behind. Absolutely."]
for i in range(12):
    acct = account(f"future_stack_{i + 1:02d}", random.randint(18, 110), random.randint(3, 60),
                   random.randint(900, 3200), random.randint(2500, 9000))
    text = " ".join([random.choice(openers), random.choice(middles), random.choice(closers)])
    reply(acct, text, 38 + i * 3 + random.uniform(-0.4, 0.4))

# A second template burst; several of these accounts are older, so they land in mixed territory.
variants = ["This is the biggest shift in software history. The next generation of builders is here.",
            "This is the biggest shift in software history. The next generation of builders has arrived.",
            "Biggest shift in software history. The next generation of builders is here.",
            "This is the biggest shift in software history. A new generation of builders is here."]
for i in range(8):
    older = i % 3 == 0
    acct = account(f"nextwave_{i + 1:02d}", random.randint(700, 1400) if older else random.randint(120, 400),
                   random.randint(150, 2400) if older else random.randint(30, 300),
                   random.randint(300, 900) if older else random.randint(600, 2000),
                   random.randint(800, 5000))
    reply(acct, variants[i % len(variants)], 172 + i * 4 + random.uniform(-1, 1))

# Accounts that repeat their own stock reply throughout the thread.
stock = ["Exactly this. Everyone needs to pay attention.", "Exactly this. Everyone needs to pay attention right now.",
         "Exactly this! Everyone needs to pay attention."]
for i in range(5):
    acct = account(f"agent_daily_{i + 1:02d}", random.randint(25, 90), random.randint(5, 40),
                   random.randint(1200, 2600), random.randint(6000, 12000))
    for k, start in enumerate([530, 1260, 2105]):
        reply(acct, stock[(i + k) % len(stock)], start + i * 4 + random.uniform(-0.5, 0.5))

# People coordinating an announcement: similar wording and timing, but ordinary established accounts.
notes = ["Bring a laptop.", "Beginners welcome!", "Join us.", "Notes coming soon.", "Snacks provided.",
         "Bring a side project.", "See you there."]
for i, note in enumerate(notes):
    acct = account(f"builders_club_{i + 1:02d}", random.randint(1300, 3600), random.randint(200, 2500),
                   random.randint(150, 900), random.randint(900, 6000))
    reply(acct, f"Community check-in: our Thursday study group is testing agent workflows together this weekend. {note}",
          612 + i * 7 + random.uniform(-2, 2))

# Generic filler posted far apart: repetitive text, but no timing overlap, so no coordination.
generic = ["Interesting perspective. I would like to see more real examples.",
           "This could change a lot. Let us see how it develops.",
           "Looking forward to seeing where these tools go next."]
for i in range(8):
    young = i % 2 == 0
    age = random.randint(25, 140) if young else random.randint(900, 2200)
    acct = account(f"signal_notes_{i + 1:02d}", age, random.randint(20, 400) if young else random.randint(300, 2000),
                   random.randint(800, 2500) if young else random.randint(200, 900),
                   int(age * (random.uniform(60, 140) if young else random.uniform(3, 10))))
    reply(acct, generic[i % len(generic)], 150 + i * 290 + random.uniform(-40, 40))

# Accounts that don't fit cleanly.
reply(account("new_here_dev", 12, 9, 41, 30),
      "First post here. I'm a nurse learning to code and these tools made the first month far less intimidating.", 1410)
reply(account("quick_take", 2900, 5100, 600, 21000),
      "Hot take: the biggest change is that prototypes are now cheap enough to throw away.", 4)
reply(account("prolific_poster", 3300, 12000, 800, 390000),
      "Spent the morning comparing three agents on the same bug. Only one asked a clarifying question first.", 1630)
reply(account("echo_fan", 1900, 640, 610, 5200),
      "The future is already here. AI agents will change everything. Adapt or get left behind.", 1850)
reply(account("kim_bridges", 2600, 3100, 700, 7400),
      "Honestly this might be the biggest shift in software history. The next generation of builders is here.", 960)
reply(account("plain_agree", 1400, 300, 280, 2100), "Exactly this. Everyone needs to pay attention to the review step.", 2240)

rows.sort(key=lambda r: r["createdAt"])
replies = [{"id": f"reply_{i + 1:03d}", "author": r["author"], "text": r["text"],
            "createdAt": r["createdAt"].isoformat().replace("+00:00", "Z")} for i, r in enumerate(rows)]
data = {"post": {"id": "post_001", "author": "alexchen",
                 "text": "AI agents aren’t just changing how we write code. They’re changing who gets to build.\n\n"
                         "The next era of software is going to look very different.",
                 "createdAt": BASE.isoformat().replace("+00:00", "Z")},
        "replies": replies}
out = Path(__file__).resolve().parent.parent / "src/main/resources/demo-conversation.json"
out.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
print(f"{len(replies)} replies from {len(accounts)} accounts -> {out}")
