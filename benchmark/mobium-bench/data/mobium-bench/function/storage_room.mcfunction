# 1,000 closed chests and 300 signs with text
# Run as the player: /function mobium-bench:storage_room
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -52.12 -2815.5 0 10
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building storage_room, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_storage_room 20t replace
