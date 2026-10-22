# Every type the static cache handles, for pixel comparisons
# Run as the player: /function mobium-bench:cache_gallery
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -57.4 -2815.5 0 12
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building cache_gallery, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_cache_gallery 20t replace
