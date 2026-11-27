# Auto Parkour (Fabric, 1.21.11)

## GUI
Press **K** to open the GUI: tabs for Checkpoints, Status, Runs and Settings. Esc or Close to leave.
The mod stops while any menu is open, so GO closes the GUI and starts.

## Auto mode: checkpoints
1. Look at the block you want to reach (stand on it), press **C**. Repeat for each checkpoint, in order.
2. Press **G**. The mod plans the jumps with a copy of Minecraft's physics, then goes there by itself.
   **G** again stops. **X** clears checkpoints. **V** turns the debug view on/off. Don't touch the mouse while it runs.
It also stops by itself if you open a menu or take damage.

## Debug view (particles)
Green sparkles = checkpoints. White dots = planned landing spots. Blue sparks = predicted path of the current jump.
The action bar shows move number, tick and how far reality is from the prediction.

## Safety margin
A jump is only used if it still works when you start up to ~0.07 blocks off (more if it has been missing a lot).
If no route exists that way, it says so and tries tight jumps with no margin.

## Learning
After every move it compares predicted and real landing. Moves that miss are remembered per map and avoided
(banned after 2 misses). The average error widens the safety margin. Data: config/autoparkour/_learn/<map>.json

## Logs (send me these if something fails)
config/autoparkour/_logs/<map>.txt - one line per move:
OK/MISS, from, to, where it landed, landing error, biggest deviation in flight, tick where it first went off by 0.1.
If firstDev is a small number (1-5) the physics constants are wrong. If it is large (a jump tick) a collision/landing is wrong.

## Record mode (older)
R = record / save, P = play fastest saved run, \ = delete runs.

Build: `gradle build` -> build/libs/. Check gradle.properties against https://fabricmc.net/develop
