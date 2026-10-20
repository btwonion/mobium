# 500 mixed mobs (cows, villagers, zombies) with NoAI, for repeatable runs
# Run as the player: /function mobium-bench:mob_pen_static
function mobium-bench:internal/settings
scoreboard players set #ready mobium_bench 0
gamemode spectator @s
tp @s -3839.5 -48 -2815.5 0 25
forceload add -3880 -2832 -3801 -2753
tellraw @s {"text":"Building mob_pen_static, please wait…","color":"gray"}
schedule function mobium-bench:internal/build_mob_pen_static 20t replace
