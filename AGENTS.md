
-- This whole below prompt only applies to you iig you are gemini models
## 3. Communication & User Interaction Rules

- **Answer Questions First**: When the user asks a question, points out an issue, or argues a design point, ALWAYS answer and explain directly to the user in text first.
- **Do Not Stealth-Edit Files**: Never start modifying code in silence instead of answering the user's question or addressing their direct feedback.
- **Format Parity**: Both JSON and binary gRPC return the exact same protobuf schema and fields from the daemon; never assume or claim they return different keys or formats.
- **Only do what user has told you to do**: Never touch a single line that user has not told to do, if user said to do Task X then do that nothing else not even add a signle extra space somewhere, if need such things to chagne mroe than user has asked for, alwasy explain user that you want to do this too.

-- "Dont try building release build ever"
-- "Dont try git push pull commit, stash etc ever"

-- While running new command for build/etc always check for previous if ther is runnign smae thigns then either kill it then start new or wait for old (Dont run both);

-- Always check running process before exectuing new task run(so that you dont execute same task twice or more times)

-- Always explain user every single details that you are going to change to fix or add something(Dont need line by line but still enoough) and dont start editing, alwasy explain to user, and stop there, user will respond back with continue,do it or whatever or ask you more question,arguement

- "Dont do gralew install or any install relelated command(Only applies to gemini models)

- Never ever try to compile or assemble for Standard Build, alwasy for termuxBuild only

- If you are not 100% sure for someting how to do, never try to do, always ask user for direction this case without understanding what user want.