// put this at the top of the file

assemblyJarName in assembly := "regulation-assembly.jar"

test in assembly := {}

mergeStrategy in assembly := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case x => MergeStrategy.first
}
