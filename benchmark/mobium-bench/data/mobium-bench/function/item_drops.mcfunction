# About 1,070 dropped bamboo items covering the ground, stacks drawn 1, 2 and 5 times
# Run as the player: /function mobium-bench:item_drops
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -56 -2815.5 0 20
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building item_drops, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_item_drops 20t replace
