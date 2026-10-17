# Prepara o ambiente de montagem NESTA máquina (Windows, Git Bash): JDK 21 e Android SDK nas pastas do usuário.
# Use com `source ferramentas/ambiente.sh` (precisa ser source, para as variáveis ficarem no seu terminal).
# Escreve também o android/local.properties (fora do git) com o sdk.dir que o Gradle exige.

_gc_raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A forma "mista" (C:/Users/...) serve ao Gradle/Java (que não entende /c/...) e ao bash (que executa C:/...).
_gc_caminho() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

export JAVA_HOME="$(_gc_caminho "$LOCALAPPDATA/Programs/jdk21")"
export ANDROID_HOME="$(_gc_caminho "$LOCALAPPDATA/Android/Sdk")"

if [ ! -x "$JAVA_HOME/bin/java" ] && [ ! -x "$JAVA_HOME/bin/java.exe" ]; then
  echo "ambiente.sh: JDK 21 não encontrado em $JAVA_HOME" >&2
  return 1 2>/dev/null || exit 1
fi
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "ambiente.sh: Android SDK não encontrado em $ANDROID_HOME" >&2
  return 1 2>/dev/null || exit 1
fi

# O sdk.dir é um arquivo .properties, onde a barra invertida e o ':' são escape: o caminho vai com barras normais e o
# ':' da letra do disco como '\:' (C\:/Users/...). Sem isso o `./gradlew lint` falha com PropertyEscape, mesmo o
# Gradle lendo o caminho certo. Barra invertida que sobrar (ANDROID_HOME fora da forma mista) vira '\\'.
if [ -d "$_gc_raiz/android" ]; then
  printf 'sdk.dir=%s\n' "$(printf '%s' "$ANDROID_HOME" | sed -e 's/\\/\\\\/g' -e 's/:/\\:/g')" \
    > "$_gc_raiz/android/local.properties"
fi

# Pasta do repositório Maven local do Robolectric, SEM espaço no caminho. O padrão (~/.m2) tem o nome do usuário, e com
# espaço o Robolectric monta o caminho do jar com "%20" e falha ao carregar (visto em 17/10/2026, no usuário desta
# máquina). O android/app/build.gradle repassa esta variável ao JVM dos testes. Fica em android/.gradle/, que o git
# ignora; o jar (~200 MB) é baixado na primeira rodada de testes.
_gc_m2="$(_gc_caminho "$_gc_raiz/android/.gradle/robolectric-m2")"
case "$_gc_m2" in
  *" "*) echo "ambiente.sh: aviso: o caminho $_gc_m2 tem espaço; o Robolectric não vai achar o jar do Android" >&2 ;;
esac
export CASCA_M2_LOCAL="$_gc_m2"
unset _gc_m2

# java e keytool no PATH (a assinatura precisa do keytool), sem repetir a entrada a cada source. O PATH do bash usa a
# forma /c/..., porque o "C:" da forma mista seria lido como separador.
_gc_bin="$(if command -v cygpath >/dev/null 2>&1; then cygpath -u "$JAVA_HOME"; else printf '%s' "$JAVA_HOME"; fi)/bin"
case ":$PATH:" in
  *":$_gc_bin:"*) ;;
  *) export PATH="$_gc_bin:$PATH" ;;
esac
unset _gc_bin

unset -f _gc_caminho
unset _gc_raiz
echo "ambiente.sh: JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME"
