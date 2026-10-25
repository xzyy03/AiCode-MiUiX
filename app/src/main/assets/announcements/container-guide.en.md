## Package install fails?

Caused by hard links being unavailable. Add this proot argument:

```
--link2symlink
```

Edit image → proot arguments → add → save, re-enter terminal.

> Downloaded-image imports already include it.

## Terminal keys broken (arrows show ^[[A etc.)?

The current shell is sh, which has no line editing. Edit the container and set Shell to:

```
/bin/bash
```

bash supports arrow keys, history and completion.
