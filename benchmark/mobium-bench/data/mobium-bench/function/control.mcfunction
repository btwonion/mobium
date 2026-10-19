# Empty superflat, nothing to accelerate
# Run as the player: /function mobium-bench:control
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -53.6 -2815.5 0 0
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building control, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_control 20t replace
