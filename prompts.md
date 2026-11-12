I want the Quire (which is a Notion clone that runs on the local machine, an browser extension based web application, opens the web page after click browser extension icon's popup window). I don't want it as a cloud deployable project at all. so restructure the blueprints and what so ever.

It reads the md files from the local machine (Folder that has been mapped by the user) and display it as a relevant notion elements. For example




It reads the md files from the local machine (Folder that has been mapped by the user) and display it as a relevant notion elements. For example



- Every newline in md file is a separate block in notion


- For the code blocks use this format (``` your code ```) 
- inline code block `your code`

- Gather data of how markdown file can be structured from this site - Https://www.freecodecamp.org/news/github-flavored-markdown-syntax-examples/

- If you don't clarity on Notion blocks, use Reverse Engineering anything (REA) skill

- we gonna use the same stack in order to get the same result, so what would that be? Research and finalize and write it in plan.md

- Don't use any subagents for designing, architectural plan or for CI review. Do the designing and architectural planning yourself and test it in the cursor browser


If we were going to implement sub pages, the files has to be stored in some structures, right? suggest me the optimal and best one.

## Structure 1

Root Folder
|
|— README.md
|
|— (Sub Folder) 
|     |
|     |—— README.md
|     |—— (Nested folders) 
|— 


## Structure 2

Root Folder
|— README.md
|
|— file1.md
|— file2.md 
|— file3.md

- Main file (README.md) is the only routing point for the everyother file in the space
- If user has to go through the any other files in the folder, the file has to be listed in the README.md



## AI  feature

- AI -> context file / system prompt

- All the AI features runs locally
- users can put it there API for gemini and then start using all the AI features
- first users has to select  which AI model  they gonna use, then lazy loads that LLM sdk



- Git track check (*mandatory) and current branch indicator (bar)



## Concerns

### Hyperlinks and subpage

- so if represent hyperlinks as `[link](./product.md)` then how do we describe the


-------------------

Create an interactive UI block for better understanding of concepts  For example: Git merge vs rebase - explain it with a visual graphics 

- should we have create a separate slash command for this or 
- shall just put that HTML code into the block?

I  think the best is creating a separate slash command, isn't it?


---


Storage Location: HTML code - sometimes goes beyond 100 of lines, so we have store it in a separate file path and load into the notes block.

First give me an example of how it looks .... color monochrome or simplistic contrast color (like in the text books)







The AI is for page summarization or vacation planning or what so ever. If it requires separate server to achieve it, proceed


How should Quire run Git?

isomorphic-git inside the extension: nothing extra to install; HTTPS remotes only, with a personal access token; no
SSH keys

Native helper app that runs the user's real git: uses existing SSH keys and credentials; needs a one-time installer for
each OS

A

B
How should Quire protect against data loss through Git?

Auto-commit after saves (batched after a short idle time) and auto-push; the status bar shows branch, unpushed
commits, and remote status

Status bar shows branch and remote status, and warns about uncommitted or unpushed work; the user commits
and pushes with a button

Auto-commit locally; the user pushes with a button; warn when unpushed

-----------

A

B

The native helper is needed for Git anyway. Should it also read and write the .md files?

Yes: the helper does file reading and writing and Git; the user picks the folder once (Recommended)

No: the browser reads and writes files, and the helper only runs Git (the user also gives the helper the folder path)


--------------


- How do we keep track of orphaned sub pages?
- Don't use any subagents for designing, architectural plan or for CI review. Do the designing and architectural planning yourself and test it in the cursor browser


Build a Notion clone, every features should exactly be like Notion but keep the project name as something else. Tech stack - for frontend I think they use Next js and figure out other stacks (come up optimal one)

optimize the guardrails for AI Agent. Do not elaborate it or exaggerate it. keep it short and simple


keep in mind that Orchestrator should not just give the other agents a big chunk of work [because the model has only 300k context window], instead it should iteratively build the application using these agents

Github Actions CI is the only compile and Build authority, there is no local runtime. By default use Blueprints skill for  managing context. first come up with a plan and write Agent file for cursor. Build our application with aesthetics and intuitive UI. 


architect doesn't going to run build commands, it's job is to calculate the feasibility, trade-offs and future scope for better scalability and maintainbility
I have described the Agent requirements vaguely, coming up with best config is your job

considering the following, whil agent file, 




If there is any architectural flaws/quirks - come up with a plan. Only proceeed further if it is approved. 
you can always propose optimal implementation than in the blueprint , only proceed further if user say approved it

This project right now has many flaws, a lot features / functionalities isn't working as expected. instead of directly editing it, ask questions of "how it could have been implemented better?" or "Stick with the existing system."


Stick with the existing Privileged broker and cleanup [as described in blueprints].
For [clean up for browser, system, temp files and so on] clearing system-level files - on a scheduled date and time. I think there is no UI for user to set these things up. so Create one


## README.md


--------------

### Engineering practices

## Guardrails

Bind every agent, including subagents. Hard rules come first; none may be traded off.

### Hard rules
- Never use the word (`N-I-K-E`, any case) in code, file or folder names, branches, or commits.
- Take project context only from `architecture.md` and `blueprints/`; ignore the root and git folder names.
- Never read `prompts.md` or add it to `.gitignore`.

### Build and CI
- GitHub Actions CI is the only build authority. Nothing runs locally.
- Read CI logs with `gh`. If `gh` is unavailable, stop and ask the user for the logs.

### Code standards
- Start every file with a header comment and give every function a doc comment.
- Production-grade code only. No mocks or placeholder data.

### Documentation
- Manage context with the `blueprints` skill.
- Never use GitHub Actions run IDs as verification status in blueprints.
- Store diagrams in `assets/diagrams/` and wireframes in `assets/wireframes/`.



## Git commands
```
Remove-Item -Recurse -Force .git
rmdir /s /q .git

git init
git log --oneline

echo "# n-op" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/<>.git
git push -u origin main

git remote set-url origin git@github.com:tharun-extinct/<project-name>.git
ssh -T git@github.com

```



## Migration Checklist

- [ ] .cursor/..
- [ ] AGENTS.md
- [ ] prompts.md
- [ ] Design files / Wireframes (Excalidraw)

- [ ] Brand word check
- [ ] README.md



## PROMPTS

### AGENT Instruction definition guidelines

- AGENT instruction should not be, too prescriptive or too vague, It should be inbetween somewhere.
- Do not elaborate or exaggerate instruction for the AGENT; keep it clean and concise




------------


- Generate images that are **visually identical or extremely close** to the provided UI reference

- Diagrams/ web pages / Explainer videos (create a 3Blue1Brown style video explainer on X. ElevenLabs for audio narration)



## CURSOR

- Codebase Indexing (RAG? Agentic RAG?)

- Tools pruning 

- AGENTS.md (Rewriting - Repeated mistake (Trial and error approaches) - let's say terminal command execution, getting it right after multiple attempts / approaches). Things get it right after multiple attempts

- /summarize, /visualize

- Execution and Approvals > Auto-Review

- `is_background: true` — the main agent starts the subagent and keeps working without waiting for it to finish.

- Fork - Agents Window > Right Click

- Keyboard Shortcuts —— `Cmd+I` (open agent), `Cmd+/` (change models)

- Plan Mode ——> AI research your codebase and generate a structured implementation plan

- **`.cursorignore`:** keeps the file out of Cursor's AI features and indexing. 

Sidepanel chat → **Ctrl+I** or **Ctrl+L**
Agent Layout → **Ctrl+E** (toggle)
Agents Window → Open Agents Window


## AGENTIC Engineering practices

- Plan ——> code (context) ——> Build & Test ——> Review
- Faster != Better          ——>     Incremental developement

### Context Hygiene

- Tools pruning
- Sub-agents definition / description
- 

- JS / node js / java (JDK21) based builds; run it locally

### Prompt structure

```
    <rules>                                     ——  AGENTS.md
    <context - (Feature requirement, task...)>  
        <problem>
        <behavior>
    </context>
    <contraints>                                ——  Don't change any other modal behavior or layout.
```

--------------


## Agents 

- To gain an additional perspective, review agent

## AGENT HQ


Design specialized subagent for Cursor [ Reviewer]. Describe its role and how it should respond. Give description for each agent (cursor) and if additional property required - add them as well.



Research Agent - Research for the existing product's system, features.

Designer - responsible for designing Aesthetics and intuitive UI,  UI workflows. Returns Design plan with granular details. currently it writes code

Coder - writes code consider every possible edge cases

Productmind - Researches [external media like relevant subreddit page, ] what existing product failed address or could have done better that speaks for the product itself. can only be called by {Designer, Research Agent}

Reviewer - reviews code by build error check via gh-cli CI logs, polls and returns what actually causing build fails/ errors

Planner - comes up with a plan, proceeds further if it is approved. writes plan in md file and wait for approval. Planner can invoke these Agents {Architect, Researcher, productmind}


---


subAgent - Research, review through CI logs


- Also come up with the list of best model that are best fit that specific role. [Do web research, get data from only legit sites. Do not spawn subAgents for this task]



ROADMAP.md

-Phases of implementation plan. It should have iterative improvement and refinement of features and workflows... with granular details 


In the Agent Orchestration workflow, the main agent has to write code here after (No coder Agent). Do not write instructions like you will have to write code, blah blah blah. keep it short and simple and remove Coder Agent from AGENTS.md. 


-----------------





**Agent Layout** is Cursor’s agent-first IDE arrangement: the window is organized around chatting with the agent, running tools, and reviewing changes—not only a code editor with chat bolted on the side.

### How it differs from other UIs

| Surface | What it is | Typical shortcut |
|--------|------------|------------------|
| **Sidepanel chat** | Agent chat in a side panel; editor stays VS Code–like | **Ctrl+I** or **Ctrl+L** |
| **Agent Layout** | Whole-window layout tuned for agent work (sidebars flip/rearrange) | **Ctrl+E** (toggle) |
| **Agents Window** | Separate agent-focused window (multi-agent, worktrees, diffs) | Command Palette → **Open Agents Window** |

Opening the sidepanel does **not** automatically switch Agent Layout. **Ctrl+E** toggles the layout mode; **Ctrl+I** toggles the chat panel.

Community reports (not always spelled out in docs): in **Editor** layout the main sidebar (Explorer, Git) is often on the **left**; in **Agent** layout agents may be on the **left** and the classic sidebar on the **right**.

### What you get in Agent Layout

Same agent features as elsewhere—modes (Agent, Ask, Plan, Debug), diffs, checkpoints, queued messages, browser tools—but the chrome is arranged for that workflow. Browser docs also call out extra session isolation when the integrated browser runs in this layout.

### Related: “Agents Window”

That’s a **different surface**: projects/agents list on the left, chat in the center, **Files / Terminal / Browser / Changes** on the right. Use it when you want a dedicated agent desk, not just **Ctrl+E** inside the normal editor window.

### Settings you might see

- **Cursor Settings → Agents** — auto-run, browser approval, usage summary, etc.
- **`cursor.agent_layout_browser_beta_setting`** — community/forum beta flag for browser-in-layout; not in the main docs index.

Docs: [Keyboard shortcuts](https://cursor.com/docs/reference/keyboard-shortcuts), [Agent overview](https://cursor.com/docs/agent/overview), [Agents Window](https://cursor.com/docs/agent/agents-window).




For a new task, start a fresh chat instead (Ctrl+N in the IDE, /clear in the CLI). To bring over just part of an old chat, mention it with @Chats, or use /fork from a specific message.

Alternatives: /clear for a new session, /fork to branch from a message.

Start a new chat (/clear or /new in the CLI)


-----------------












The agent finds the library's ID, then pulls the relevant docs into its context.

**Name the library directly** to skip the lookup step. Use its ID, written as `/owner/repo`:

> Implement Supabase auth with email magic links. use library /supabase/supabase

**Make it automatic with a rule.** Create `.cursor/rules/context7.mdc`:

```markdown
---
description: Use Context7 for library documentation
alwaysApply: true
---
When writing code that uses a third-party library or framework, or when generating
setup/configuration steps, use the Context7 MCP tools to fetch current docs
before answering.
```


## Note for this repo

Context7 ....`researcher` and `architect` --- checking library APIs and versions. That would mean mentioning it in their files under `.cursor/agents/`. The remote option also fits your "nothing runs locally" rule, since no local process is involved.



- If you have branch conflict, while commiting, stop and ask user


------------





A worktree is a second working folder attached to the same git repository. Each folder has a different branch checked out, and you can edit both at the same time.

Normally a repository has one folder (here `c:\n-tracker`), and it can only show one branch at a time. Switching branches swaps the files in that folder. `git worktree add <folder> <branch>` creates another folder with a different branch checked out, and both folders share the same history, commits and remote. A commit made in either folder is immediately visible from the other, because there's only one repository underneath.

That's why I used one:
- Another session had `c:\n-tracker` on the `promo-video` branch.
- I needed to work on `slice-2-project-shell` without switching branches under that session.
- So I ran `git worktree add C:\tron-slice2 slice-2-project-shell`, which gave me a second folder showing the Slice 2 branch.
- I committed and pushed from there, then removed it with `git worktree remove`.

Removing a worktree only deletes that extra folder. The commits made in it stay in the repository, so nothing was lost.

Useful commands:
- `git worktree list` shows every folder attached to the repository.
- `git worktree add <path> <branch>` creates one.
- `git worktree remove <path>` deletes one. Git refuses if the folder has uncommitted changes.


-------

`.prettierignore` lists the files and folders that Prettier, the code formatter, should skip. It works like `.gitignore`, but it controls formatting instead of git tracking.

CI runs `prettier --check .`, which fails the build if any file isn't formatted the way Prettier wants. Some files must never be reformatted, so they're listed here. These two entries came up in the conflict:

- **`crates/core/tests/golden`**: these are golden test fixtures, the exact KiCad and `tron.json` files the Rust tests compare saved output against byte for byte. If Prettier reformatted `tron.json`, the comparison would fail even though the code is correct.
- **`promo`**: the promo video project has its own Prettier check in its own CI workflow (`promo.yml`). The root ignore keeps the main app's check from also checking it.

The conflict happened because each branch added its own line in the same spot. The fix was to keep both lines.

The other entries are generated or vendored content where formatting doesn't matter or is controlled elsewhere:
- Build output: `dist`, `target`, `src-tauri/target`, `src-tauri/gen`.
- Icons: `src-tauri/icons`.
- Generated TypeScript bindings: `ui/src/bindings`, produced by `ts-rs` and checked by CI for drift instead.
- Lockfiles: `package-lock.json`.
- Markdown and config folders: `*.md`, `.cursor`, `.github`.



-----------



I used a worktree because a hard reset in `c:\n-tracker` would have caused damage there and still wouldn't have fixed the problem.

**What a hard reset would have done:**
- **Deleted your staged files:** `git reset --hard` throws away everything staged. Because `README.md` and `prompts.md` were newly added and not yet committed, they would have been deleted from disk.
- **Changed the other session's files:** that session had switched this folder to `promo-video` and was committing there. A reset or branch switch in the same folder would have changed the files it was working with.
- **Left the real job undone:** a reset only removes my commit from `promo-video`. The commit still had to be added to `slice-2-project-shell`.

**Why I couldn't just point the branch at the commit:** my commit's parent was the latest promo commit. Pointing `slice-2-project-shell` at it would have pulled all the promo-video commits into Slice 2. I had to cherry-pick the commit onto the Slice 2 branch, and that needs a checkout of that branch to resolve conflicts like the `.prettierignore` one. A separate worktree gave me that checkout without touching this folder.

**The worktree wasn't the only option.** I could have stashed your staged files, switched this folder to `slice-2-project-shell`, cherry-picked, and switched back. That's how I handled the final merge once the promo work was finished. Doing that earlier was only risky while the other session might still be using the folder. Looking back, the worktree caused more confusion than it was worth, and I won't use one again unless you ask.




-----------



No push needed reverting, because the misplaced commit (`7e56d21`) was never pushed to `promo-video`. It only existed in your local copy of the branch. My push command named `slice-2-project-shell` explicitly, and the copy of `promo-video` on GitHub stayed at `b28393c`, the other session's last promo commit. I confirmed that with `git fetch` and `git rev-parse origin/promo-video`.

To remove the commit locally, I ran this in `c:\n-tracker`:

```powershell
git reset --keep HEAD~1
```

This moved `promo-video` back one commit, to `b28393c`. I chose `--keep` over `--hard` because:
- **It's selective:** it only rewrites files that the removed commit changed, which are the Slice 2 files.
- **It keeps other uncommitted changes:** a hard reset would have discarded them.
- **It refuses rather than overwrites:** if the reset would have clobbered a local edit, it stops instead.

It did unstage your `.gitignore`, `README.md` and `prompts.md` without changing their contents, so I staged them again straight away.

Nothing was rewritten on GitHub, so there was no force-push. The commit's contents weren't lost either: a corrected version is on `main` through [PR #3](https://github.com/tharunkumar1177/tron/pull/3), and the original stays in git's local history log until it expires.








--------------------






For a "never read this file" instruction, there's almost no difference. Both are just text added to the model's prompt, and neither one actually stops a file from being read. They differ in how and when the instruction gets loaded.

**`AGENTS.md`**
- Plain markdown with no configuration.
- The root file is always included in every agent request. A nested `AGENTS.md` in a subfolder applies when working in that folder.
- It's the simplest choice for project-wide guardrails

**`.cursor/rules/*.mdc`**
- Each rule has frontmatter (`description`, `globs`, `alwaysApply`) that controls when it's loaded:
  - **Always** `alwaysApply: true`
  - **Auto-attached:** loaded when files matching `globs` are in context.
  - **Agent-requested:** the model decides from the `description` whether to pull it in.
  - **Manual:** - @-mention the rule

**The catch for your case:** don't use an auto-attached rule with `globs: prompts.md`. It would only load once `prompts.md` is already in context, which means after it has been read. A "don't read" rule has to be always-on, so `AGENTS.md` or an `alwaysApply: true` rule are equivalent here.

**If you want actual enforcement rather than a request:**
- **`.cursorignore`:** keeps the file out of Cursor's AI features and indexing. 





 read-blocking hook to enforce it.



**What each part blocks:**
- **`beforeReadFile`:** any agent file read of `prompts.md`.
- **`beforeShellExecution`:** commands that mention it, such as `cat prompts.md`, `Get-Content .\prompts.md`, or `type prompts*`. The `prompts` matcher means the script only runs for those commands, not every shell command.
- **`preToolUse`:** a Read or Grep aimed directly at the file or a `prompts*` glob.


**Gaps:**
- A Grep over the whole repo can still match lines inside `prompts.md`. Listing the file in `.cursorignore` closes that.
- A determined workaround, like building the filename from pieces in a script, gets past any string match. Hooks stop accidental reads, not adversarial ones.
- If the script crashes, the action is allowed (it fails open). I left `failClosed` off on purpose: with it on, a broken script would block every file read in the project.

the block will show up in the **Hooks** output channel. If the hooks don't appear in Cursor's **Hooks** settings tab, restart Cursor.




Never consider project root folder; archictecture.md is the only source of truth for project data


// (`.cursor/hooks.json` with a `beforeReadFile` hook): runs a script that denies reads of `prompts.md`.
// A `beforeShellExecution` hook can also catch `cat prompts.md` and similar commands.



The main agent follows `.cursor/orchestrator.md`; if you are the main agent and haven't received it this session, read it before acting. Subagents ignore it and follow their own file in `.cursor/agents/`.

skip the linux and MacOS helper build and shipping while you run it build and compilation check. And when it's goes for public, I shall run all the OS build from the main worflow dispatch. can you able to skip using gh cli?


- skip the test on the CI


since the workflow (GH Actions) is running on the current work branch, why does it have to run on the merge too (merge on the main), just add the workflow dispatch and remove the unnecessary run on the main. 

---

since we need only the windows version of the helper, why can't you skip the other OS's build save time and compute power / cost. Give me a rule to add it to the AGENTS.md


skip the linux and MacOS helper build and shipping while you run it build and compilation check. And when it's goes for public, I shall run all the OS build from the main worflow dispatch. can you able to skip using gh cli?




Ctrl + Z is not undo-ing the deleted block


write the e2e as a separate workflow.


(Can't you run a ) Target specific tests 

- Resource limitation and optimization for better yeild with in low time and cost




------


fix Orchestration instruction, even ask gh workflow for sub agents
Thought tool? - thinking tokens?
always Squash Merge on PR
CI runtime rules (Target test execution in CI, instead of run the whole test files, which have passed earlier)
Don't add --trailer "Co-authored-by Cursor"

gh pr create --repo tharunkumar1177/tron --base main --head slice-1-walking-skeleton --title "Slice 1: walking skeleton with Tauri shell, CI, and releases" --body $body 2>&1 -- $body



PR (pr also merge the code changes, right) vs Merge
thoughts tools control?
CRLF vs LF
If some changes staged by me, commit it with the changes you've made. 

How did you get back the action tokens and how ide parses it?
Reviewer counldnot run shell commands? why?




wire diagrams

(like it used to be in the Gemini diagrams, .







# Agentic Engineer

Don't insist on skills for user journeys, that LLM is already pretty good at
Skills are meant to supplement the default knowledge of an LLM
Figure out which scenario requires SKILLS
Less is More - Find the minimum scope and the length of a skill that provides the maximum benefits. Generally succinct skills perform better than the very very lengthy ones. LLM starts forgetting if you throw too much context their way


when and how the LLM should use these files(supporting files- loading it on demand, when it's necessary)!
- task routing, if it's already done/ exist, don't refer that file






what are the things, you did not get it right the first time,but figured out through trail and error 


Create it as Agent rule

CI targeted test execution - rule

Don't specify for this project only...


