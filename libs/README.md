# libs/

The two mods this project compiles against are not on any public maven repository,
and they are **not** ours to redistribute, so their jars are gitignored.

Drop these here before building:

| file | where to get it |
| --- | --- |
| `dashpanels-2.1+neoforge1.21.1.jar` | the Modrinth / CurseForge page for Dashpanels |
| `drivebywire-0.3.0.jar` | Drive By Wire for NeoForge 1.21.1 |

`build.gradle` picks them up with `compileOnly fileTree(dir: 'libs', include: ['*.jar'])`,
so any jar placed here is on the compile classpath automatically.