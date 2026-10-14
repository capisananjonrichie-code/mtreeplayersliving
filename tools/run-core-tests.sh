#!/bin/bash
# Usage: tools/run-core-tests.sh   (from the project root)
set -e
LIB=${LIB:-/usr/share/java}
OUT=$(mktemp -d)
CORE='/(bot/(SkinSpec|LifecycleState|Trait|Personality|PersonalityTemplate|PersonalityGenerator|CombatSkill|CombatStyle|LongTermGoal|NameRules|BotLocation|BotRecord|BotCodec)|storage/[A-Za-z]+|nav/(Pathfinder|StuckDetector)|social/(Stance|SocialEvent|Relationship|RelationshipModel|MemoryStore|Rumor|RumorBook|FactionRegistry|FactionFormation|SocialState)|chat/(Intent|IntentClassifier|Compliance|ChatLimiter|ReplySanitizer|ActivitySummary|DialogueEngine|LanguageModelAdapter|HttpLanguageModelAdapter)|building/(Blueprint|BlueprintParser|ProgressBits|SiteSelector|BuildPolicy)|survival/(BreakTime|Recipes|SurvivalPlanner|InventoryPlanner)|protect/BlockPolicy|combat/(WeaponStats|AttackCooldown|EquipmentEvaluator|FoodTable|HungerModel|SkillProfile|CombatBrain|TargetSelector)|director/(BlockClass|TerrainProbe|SpawnSafety|PopulationPlanner|EncounterTracker|Annulus|BlockNames)|core/Settings)\.java$'
mkdir -p $OUT/main $OUT/test
javac --release 21 -Xlint:all -d $OUT/main -cp $LIB/gson.jar $(find src/main/java -name '*.java' | grep -E "$CORE")
TCP=$OUT/main:$LIB/gson.jar:$LIB/junit-jupiter-api.jar:$LIB/junit-jupiter-params.jar:$LIB/opentest4j.jar:$LIB/apiguardian-api.jar:$LIB/junit-platform-commons.jar
javac --release 21 -Xlint:all -d $OUT/test -cp $TCP $(find src/test/java -name '*.java')
java -jar $LIB/junit-platform-console-standalone.jar execute --disable-banner --details=summary \
  --class-path $OUT/main:$OUT/test:$LIB/gson.jar:$LIB/sqlite-jdbc.jar:$LIB/slf4j-api.jar:$LIB/slf4j-nop.jar --scan-class-path $OUT/test
