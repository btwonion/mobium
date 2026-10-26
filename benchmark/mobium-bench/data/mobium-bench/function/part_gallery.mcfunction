# Models the GPU part transform expands besides the mob pens, for pixel comparisons
# Run as the player: /function mobium-bench:part_gallery
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -57.8 -2815.5 0 10
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building part_gallery, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_part_gallery 20t replace
