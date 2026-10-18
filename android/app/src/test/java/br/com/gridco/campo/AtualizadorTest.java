package br.com.gridco.campo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

/**
 * A atualização da casca: o versao.json da Release, a regra das 6 h, o SHA-256 do APK e o caminho até o instalador. A
 * rede, as telas e o relógio são falsos (os testes de JVM não fazem rede), e o executor é direto, então cada fluxo
 * roda inteiro dentro do verificar(). O que só se vê no aparelho (diálogo de verdade, instalador do sistema) fica para
 * a prova lá.
 */
@RunWith(RobolectricTestRunner.class)
public class AtualizadorTest {

    // sha256("abc") e sha256("abd"), gerados à parte pelo Node (não pelo código sob teste).
    private static final String SHA_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA_ABD = "a52d159f262b2c6ddb724a61840befc36eb30c88877a4030b65cbe86298449c9";
    private static final String SHA_VAZIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    // 200 000 bytes, o byte i valendo i % 251: passa de longe o buffer da leitura em pedaços.
    private static final String SHA_GRANDE = "e24bc62381f1224fbbb74688663f8f9743b9680b193edd666835e97b06e730eb";
    private static final String APK_CERTO =
        "https://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1.2.0/app-campo.apk";
    private static final long UMA_HORA_MS = 3_600_000L;
    private static final long SEIS_HORAS_MS = 21_600_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SharedPreferences prefs;
    private File pasta;
    private AtomicLong agora;
    private AtomicReference<Object> dono;
    private RedeFalsa rede;
    private TelasFalsas telas;
    private int versaoAtual;

    @Before
    public void preparar() {
        ShadowLog.clear();
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("teste_atualizacao", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        pasta = new File(tmp.getRoot(), "atualizacao"); // não existe: quem baixa é que a cria
        agora = new AtomicLong(1_800_000_000_000L);
        dono = new AtomicReference<>();
        rede = new RedeFalsa();
        telas = new TelasFalsas();
        versaoAtual = 10;
        rede.json = json(12, "1.2.0", APK_CERTO, SHA_ABC, "Fotos mais rápidas", "Correção do GPS");
        rede.apk = bytes("abc");
    }

    // Executor direto (o fluxo roda inteiro dentro do verificar()), mas que guarda o que uma tarefa deixaria escapar: no
    // aparelho, exceção que sai da tarefa derruba o app pela thread do pool.
    private final List<Throwable> escapou = new ArrayList<>();
    private final Executor direto = tarefa -> {
        try {
            tarefa.run();
        } catch (Throwable t) {
            escapou.add(t);
        }
    };

    private boolean depuracao;

    private Atualizador novo() {
        return new Atualizador(prefs, pasta, versaoAtual, agora::get, rede, telas, direto, dono, depuracao);
    }

    @After
    public void nadaEscapouDeUmaTarefa() {
        assertEquals("exceção que escapou de uma tarefa: " + escapou, 0, escapou.size());
    }

    // ---------------------------------------------------------------- VersaoRemota

    @Test
    public void leVersaoValida() {
        VersaoRemota v = VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC, "Fotos mais rápidas", "Correção do GPS"));
        assertEquals(12, v.versionCode);
        assertEquals("1.2.0", v.versionName);
        assertEquals(APK_CERTO, v.apk);
        assertEquals(SHA_ABC, v.sha256);
        assertEquals(Arrays.asList("Fotos mais rápidas", "Correção do GPS"), v.novidades);
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaApkDeOutroEndereco() {
        VersaoRemota.ler(json(12, "1.2.0", "https://exemplo.com.br/app-campo.apk", SHA_ABC, "x"));
    }

    @Test
    public void recusaApkForaDaPastaDeReleasesDoRepositorio() throws Exception {
        String base = "https://github.com/Grid-Co-CODE/app-campo-android/releases/download/";
        for (String apk : new String[] {
                "http://github.com/Grid-Co-CODE/app-campo-android/releases/download/v1/app-campo.apk", // sem TLS
                "https://github.com/Grid-Co-CODE/outro-repo/releases/download/v1/app-campo.apk",
                "https://github.com/Grid-Co-CODE/app-campo-android/releases/latest/download/app-campo.apk",
                "https://github.com.exemplo.com.br/Grid-Co-CODE/app-campo-android/releases/download/v1/a.apk",
                "https://github.com/grid-co-code/app-campo-android/releases/download/v1/app-campo.apk", // caixa
                " " + base + "v1/app-campo.apk",
                base, // nada depois da pasta
                base + "../../../../outro/repo/releases/download/v1/a.apk", // sobe de pasta
                base + "v1/./app-campo.apk",
                base + "v1/app-campo.apk?token=x",
                base + "v1/app-campo.apk#x",
                base + "v1//app-campo.apk",
                base + "v1/app campo.apk",
                base + "v1\\app-campo.apk",
                base + "%2e%2e/app-campo.apk",
                base + "v1/app-campo.apk\n",
        }) {
            recusa(com("apk", apk), apk); // pelo JSONObject: a barra invertida e a quebra de linha vão com o escape
        }
    }

    @Test
    public void recusaQuemFaltaCampoOuTemTipoErrado() throws Exception {
        String completo = json(12, "1.2.0", APK_CERTO, SHA_ABC, "x");
        // Sem cada um dos cinco campos.
        for (String campo : new String[] {"versionCode", "versionName", "apk", "sha256", "novidades"}) {
            JSONObject o = new JSONObject(completo);
            o.remove(campo);
            recusa(o.toString(), "sem " + campo);
        }
        recusa(null, "nulo");
        recusa("", "vazio");
        recusa("[]", "vetor");
        recusa("não é json", "texto");
        recusa(com("versionCode", "12"), "versionCode em texto");
        recusa(com("versionCode", 12.5), "versionCode fracionado");
        recusa(com("versionCode", 0), "versionCode zero");
        recusa(com("versionCode", -3), "versionCode negativo");
        recusa(com("versionCode", 99_999_999_999L), "versionCode além do int");
        recusa(com("versionCode", JSONObject.NULL), "versionCode nulo");
        recusa(com("versionName", ""), "versionName vazio");
        recusa(com("versionName", "  "), "versionName em branco");
        recusa(com("versionName", 1.2), "versionName número");
        recusa(com("versionName", JSONObject.NULL), "versionName nulo");
        recusa(com("apk", 7), "apk número");
        recusa(com("novidades", new JSONArray().put(7)), "novidade que não é texto");
        recusa(com("novidades", new JSONArray().put(JSONObject.NULL)), "novidade nula");
        recusa(com("novidades", "x"), "novidades em texto");
        recusa(com("novidades", JSONObject.NULL), "novidades nulas");
        // O SHA-256 que nunca poderia bater: 63 e 65 caracteres, fora do alfabeto hex, ou que não é texto.
        recusa(json(12, "1.2.0", APK_CERTO, SHA_ABC.substring(1), "x"), "sha curto");
        recusa(json(12, "1.2.0", APK_CERTO, SHA_ABC + "0", "x"), "sha longo");
        recusa(json(12, "1.2.0", APK_CERTO, SHA_ABC.replace('b', 'z'), "x"), "sha fora do hex");
        recusa(com("sha256", 12345), "sha número");
    }

    /** O versao.json completo com um campo trocado. */
    private static String com(String campo, Object valor) throws Exception {
        JSONObject o = new JSONObject(json(12, "1.2.0", APK_CERTO, SHA_ABC, "x"));
        o.put(campo, valor);
        return o.toString();
    }

    @Test
    public void novidadesVaziasValemENaoViramLinhaEmBranco() {
        assertTrue(VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC)).novidades.isEmpty());
        VersaoRemota v = VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC, "  ", "Uma", ""));
        assertEquals(Arrays.asList("Uma"), v.novidades);
    }

    @Test
    public void oShaVemEmMinusculasEAListaNaoMuda() {
        VersaoRemota v = VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC.toUpperCase(), "x"));
        assertEquals(SHA_ABC, v.sha256);
        try {
            v.novidades.add("outra");
            fail("a lista de novidades não devia aceitar mudança");
        } catch (UnsupportedOperationException esperado) {
            // ok
        }
    }

    // ---------------------------------------------------------------- devoChecar

    @Test
    public void checaSoDepoisDeSeisHoras() {
        assertFalse(Atualizador.devoChecar(21_599_999L, 0L));
        assertTrue(Atualizador.devoChecar(21_600_000L, 0L));
        assertFalse(Atualizador.devoChecar(1_000L + SEIS_HORAS_MS - 1, 1_000L));
        assertTrue(Atualizador.devoChecar(1_000L + SEIS_HORAS_MS, 1_000L));
        assertFalse(Atualizador.devoChecar(5_000L, 5_000L));
    }

    @Test
    public void relogioQueVoltouChecaDeNovo() {
        // Se alguém acerta a data do aparelho para trás, "agora - última" fica negativo e nunca passaria de 6 h.
        assertTrue(Atualizador.devoChecar(1_000L, 2_000L));
    }

    // ---------------------------------------------------------------- confere

    @Test
    public void shaErradoNaoConfere() throws IOException {
        assertFalse(Atualizador.confere(arquivo("abc"), SHA_ABD));
    }

    @Test
    public void shaCertoConfere() throws IOException {
        File f = arquivo("abc");
        assertTrue(Atualizador.confere(f, SHA_ABC));
        assertTrue(Atualizador.confere(f, SHA_ABC.toUpperCase()));
    }

    @Test
    public void conferePeloConteudoInteiroELePorPedacos() throws IOException {
        byte[] grande = new byte[200_000];
        for (int i = 0; i < grande.length; i++) grande[i] = (byte) (i % 251);
        File f = tmp.newFile("grande.bin");
        Files.write(f.toPath(), grande);
        assertTrue(Atualizador.confere(f, SHA_GRANDE));
        grande[199_999] ^= 1; // um bit no último byte
        Files.write(f.toPath(), grande);
        assertFalse(Atualizador.confere(f, SHA_GRANDE));
        assertTrue(Atualizador.confere(tmp.newFile("vazio.bin"), SHA_VAZIO));
    }

    @Test
    public void naoConfereSemArquivoOuComShaInutil() throws IOException {
        assertFalse(Atualizador.confere(new File(tmp.getRoot(), "nao-existe.apk"), SHA_ABC));
        assertFalse(Atualizador.confere(tmp.getRoot(), SHA_ABC)); // uma pasta
        File f = arquivo("abc");
        assertFalse(Atualizador.confere(f, null));
        assertFalse(Atualizador.confere(f, ""));
        assertFalse(Atualizador.confere(f, "abc"));
        assertFalse(Atualizador.confere(null, SHA_ABC));
    }

    // ---------------------------------------------------------------- o fluxo

    @Test
    public void gravaAHoraDaChecagemESoChecaDepoisDeSeisHoras() {
        long primeira = agora.get();
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(1, rede.leituras);
        assertEquals(primeira, prefs.getLong("ultima_checagem_ms", -1));

        agora.addAndGet(SEIS_HORAS_MS - 1);
        novo().verificar();
        assertEquals("antes de 6 h não vai à rede", 1, rede.leituras);

        agora.addAndGet(1);
        novo().verificar();
        assertEquals(2, rede.leituras);
        assertEquals(agora.get(), prefs.getLong("ultima_checagem_ms", -1));
    }

    @Test
    public void falhaDeRedeESilencioMasContaAsSeisHoras() {
        rede.falhaAoLer = new IOException("sem sinal");
        novo().verificar();
        assertEquals(1, rede.leituras);
        assertEquals(0, telas.ofertas.size());
        assertEquals(0, telas.avisosDeFalha);
        assertEquals(agora.get(), prefs.getLong("ultima_checagem_ms", -1));
        assertNull("o fluxo termina", dono.get());

        agora.addAndGet(UMA_HORA_MS);
        novo().verificar();
        assertEquals("não insiste antes das 6 h", 1, rede.leituras);
    }

    @Test
    public void versaoIgualOuMenorNaoOferece() {
        rede.json = json(10, "1.0.0", APK_CERTO, SHA_ABC, "x");
        novo().verificar();
        agora.addAndGet(SEIS_HORAS_MS);
        rede.json = json(9, "0.9.0", APK_CERTO, SHA_ABC, "x");
        novo().verificar();
        assertEquals(2, rede.leituras);
        assertEquals(0, telas.ofertas.size());
    }

    @Test
    public void jsonInvalidoOuApkDeForaNaoOfereceEContaAsSeisHoras() {
        rede.json = json(12, "1.2.0", "https://exemplo.com.br/a.apk", SHA_ABC, "x");
        novo().verificar();
        assertEquals(0, telas.ofertas.size());
        assertNull(dono.get());
        agora.addAndGet(UMA_HORA_MS);
        novo().verificar();
        assertEquals(1, rede.leituras);
    }

    @Test
    public void versaoMaiorOferecePeloJsonLido() {
        telas.resposta = Resposta.NADA;
        novo().verificar();
        assertEquals(1, telas.ofertas.size());
        assertEquals(12, telas.ofertas.get(0).versionCode);
        assertEquals("1.2.0", telas.ofertas.get(0).versionName);
        assertEquals("sem resposta ainda, nada baixado", 0, rede.downloads);
    }

    @Test
    public void depoisNaoBaixaELiberaOFluxoParaDepoisDasSeisHoras() {
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(0, rede.downloads);
        assertNull(dono.get());
        agora.addAndGet(SEIS_HORAS_MS);
        novo().verificar();
        assertEquals("a próxima oferta vem depois das 6 h", 2, telas.ofertas.size());
    }

    @Test
    public void atualizarAgoraBaixaConfereEAbreOInstalador() throws IOException {
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, rede.downloads);
        assertEquals(APK_CERTO, rede.ultimaUrl);
        assertEquals(1, telas.instalados.size());
        File apk = telas.instalados.get(0);
        assertEquals(new File(pasta, "app-campo.apk"), apk);
        assertArrayEquals(bytes("abc"), Files.readAllBytes(apk.toPath()));
        assertEquals(0, telas.avisosDeFalha);
        assertEquals(0, telas.pedidosDePermissao);
        assertNull(dono.get());
    }

    @Test
    public void shaErradoApagaOArquivoAvisaENaoInstala() {
        rede.json = json(12, "1.2.0", APK_CERTO, SHA_ABD, "x"); // o servidor diz abd, o arquivo é abc
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, rede.downloads);
        assertEquals(1, telas.avisosDeFalha);
        assertEquals(0, telas.instalados.size());
        assertEquals(0, telas.pedidosDePermissao);
        assertFalse(new File(pasta, "app-campo.apk").exists());
        assertNull(dono.get());
    }

    @Test
    public void falhaNoDownloadApagaOParcialEAvisa() {
        // A pessoa tocou em "Atualizar agora" e viu o "Baixando": o download que cai não pode terminar em silêncio.
        rede.falhaAoBaixar = new IOException("caiu");
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, rede.downloads);
        assertFalse("o pedaço baixado não fica", new File(pasta, "app-campo.apk").exists());
        assertEquals(1, telas.avisosDeFalha);
        assertEquals(0, telas.instalados.size());
        assertEquals(0, telas.pedidosDePermissao);
        assertNull(dono.get());

        agora.addAndGet(UMA_HORA_MS);
        novo().verificar();
        assertEquals("sem nova tentativa antes das 6 h", 1, rede.leituras);
        assertEquals(1, rede.downloads);
    }

    @Test
    public void semPermissaoPedeEDepoisDeAbrirOsAjustesInstalaNoProximoResume() throws IOException {
        telas.pode = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, telas.pedidosDePermissao);
        assertEquals("não abre o instalador sem a permissão", 0, telas.instalados.size());
        assertEquals("os ajustes só abrem quando a pessoa toca", 0, telas.ajustesAbertos);
        assertNull("o fluxo libera, o resume seguinte é que continua", dono.get());

        telas.tocarAbrirAjustes();
        assertEquals(1, telas.ajustesAbertos);

        // O Android pode matar o processo ao liberar a permissão: o resume vem de uma instância nova, só com o disco.
        telas.pode = true;
        agora.addAndGet(UMA_HORA_MS);
        novo().verificar();
        assertEquals(1, telas.instalados.size());
        assertArrayEquals(bytes("abc"), Files.readAllBytes(telas.instalados.get(0).toPath()));
        assertEquals("não baixa de novo", 1, rede.downloads);
        assertEquals("nem consulta o versao.json de novo", 1, rede.leituras);

        // Uma tentativa só: o resume seguinte (a câmera, por exemplo) não reabre o instalador.
        novo().verificar();
        assertEquals(1, telas.instalados.size());
        assertEquals(1, telas.pedidosDePermissao);
    }

    @Test
    public void voltarDosAjustesSemLiberarDesisteEmSilencio() {
        telas.pode = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        telas.tocarAbrirAjustes();

        agora.addAndGet(UMA_HORA_MS);
        novo().verificar(); // continua sem permissão
        assertEquals(0, telas.instalados.size());
        assertEquals("não pergunta de novo a cada volta ao app", 1, telas.pedidosDePermissao);
        assertEquals(0, telas.avisosDeFalha);

        telas.pode = true; // liberou depois, por fora: a pendência já foi dada como desistida
        novo().verificar();
        assertEquals(0, telas.instalados.size());
    }

    @Test
    public void fecharODialogoDePermissaoSemAbrirOsAjustesNaoDeixaPendencia() {
        telas.pode = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        telas.pode = true;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
    }

    @Test
    public void ajustesQueNaoAbremNaoDeixamPendencia() {
        telas.pode = false;
        telas.ajustesAbrem = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        telas.tocarAbrirAjustes();
        telas.pode = true;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
    }

    @Test
    public void pendenciaComArquivoSumidoNaoInstala() {
        deixarPendente();
        assertTrue(new File(pasta, "app-campo.apk").delete());
        telas.pode = true;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
        assertEquals(0, telas.avisosDeFalha);
    }

    @Test
    public void pendenciaComArquivoAlteradoNaoInstalaEApagaOArquivo() throws IOException {
        deixarPendente();
        File apk = new File(pasta, "app-campo.apk");
        Files.write(apk.toPath(), bytes("abd")); // o cache do app é de todos os processos do aparelho: mudou
        telas.pode = true;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
        assertFalse("não fica APK que não bate", apk.exists());
        assertEquals(0, telas.avisosDeFalha);
    }

    /** O APK baixado e conferido, sem a permissão, e a pessoa tocou em "Abrir ajustes". */
    private void deixarPendente() {
        telas.pode = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        telas.tocarAbrirAjustes();
        assertEquals(1, telas.ajustesAbertos);
        assertTrue(new File(pasta, "app-campo.apk").isFile());
    }

    @Test
    public void pendenciaDaVersaoQueJaEstaInstaladaApagaOArquivo() {
        telas.pode = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        telas.tocarAbrirAjustes();

        telas.pode = true;
        versaoAtual = 12; // a instalação aconteceu por fora
        novo().verificar();
        assertEquals(0, telas.instalados.size());
        assertEquals(0, pasta.list().length);
    }

    @Test
    public void umFluxoPorVezEReabreDepoisDeDarAResposta() {
        telas.resposta = Resposta.NADA;
        Atualizador a = novo();
        a.verificar();
        a.verificar(); // o resume que vem logo depois do load()
        novo().verificar(); // e a tela recriada, com outra instância, no mesmo processo
        assertEquals(1, rede.leituras);
        assertEquals(1, telas.ofertas.size());

        telas.adiou.run();
        assertNull(dono.get());
        agora.addAndGet(SEIS_HORAS_MS);
        novo().verificar();
        assertEquals(2, rede.leituras);
    }

    @Test
    public void apagaOsApksVelhosAntesDeAbrirOInstalador() {
        // As sobras aparecem durante o download (o que a checagem já tinha limpado não conta): só o APK novo fica.
        rede.sobras = new String[] {"app-campo-antigo.apk", "app-campo.apk.parcial"};
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, telas.instalados.size());
        assertEquals("na hora de abrir o instalador só o APK novo está na pasta",
            Arrays.asList("app-campo.apk"), telas.nomesNaPastaAoInstalar);
    }

    @Test
    public void naChecagemApagaOQueSobrouDaUltimaAtualizacao() throws IOException {
        assertTrue(pasta.mkdirs());
        Files.write(new File(pasta, "app-campo.apk").toPath(), bytes("abc")); // a que acabou de ser instalada
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(0, pasta.list().length);
    }

    @Test
    public void erroInesperadoNaoDerrubaNemPrendeOFluxo() {
        rede.erroInesperado = new IllegalStateException("bug");
        novo().verificar();
        assertNull(dono.get());
        agora.addAndGet(SEIS_HORAS_MS);
        rede.erroInesperado = null;
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(1, telas.ofertas.size());
    }

    @Test
    public void erroInesperadoNoDownloadSoltaOFluxoESemInstalar() {
        rede.erroInesperadoAoBaixar = new SecurityException("sem permissão");
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, rede.downloads);
        assertEquals(0, telas.instalados.size());
        assertEquals("o erro antes do instalador também avisa", 1, telas.avisosDeFalha);
        assertNull(dono.get());
    }

    @Test
    public void executorQueRecusaNaoPrendeOFluxo() {
        Executor recusa = tarefa -> { throw new RejectedExecutionException("encerrado"); };
        new Atualizador(prefs, pasta, versaoAtual, agora::get, rede, telas, recusa, dono, false).verificar();
        assertNull(dono.get());
        assertEquals(0, rede.leituras);
    }

    @Test
    public void telaEncerradaNaoComecaFluxoENemPrendeADeOutra() {
        telas.resposta = Resposta.NADA;
        Atualizador velha = novo();
        velha.verificar(); // a oferta está aberta
        velha.encerrar(); // a tela sai: o diálogo é fechado, e isso conta como "Depois"
        assertTrue(telas.encerrou);
        assertNull(dono.get());

        velha.verificar();
        assertEquals("a instância encerrada não volta a checar", 1, rede.leituras);

        agora.addAndGet(SEIS_HORAS_MS);
        novo().verificar(); // a tela recriada segue
        assertEquals(2, rede.leituras);
    }

    @Test
    public void oLogNaoLevaEnderecoNemNomeDeArquivo() {
        // Rede caindo, JSON torto, SHA errado e download cortado: tudo o que a atualização sabe contar.
        rede.falhaAoLer = new IOException("falhou em " + APK_CERTO + "?token=segredo");
        novo().verificar();
        agora.addAndGet(SEIS_HORAS_MS);
        rede.falhaAoLer = null;
        rede.json = "{" + "\"apk\":\"" + APK_CERTO + "?token=segredo\"}";
        novo().verificar();
        agora.addAndGet(SEIS_HORAS_MS);
        rede.json = json(12, "1.2.0", APK_CERTO, SHA_ABD, "x");
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        agora.addAndGet(SEIS_HORAS_MS);
        rede.falhaAoBaixar = new IOException("caiu em " + APK_CERTO + "?token=segredo");
        novo().verificar();
        agora.addAndGet(SEIS_HORAS_MS);
        rede.erroInesperado = new IllegalStateException("em " + APK_CERTO + "?token=segredo");
        novo().verificar();

        assertFalse("o teste devia ter gerado log", ShadowLog.getLogs().isEmpty());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            String linha = item.tag + " " + item.msg + (item.throwable == null ? "" : " " + item.throwable);
            for (String proibido : new String[] {"http", "://", "github", "token", "segredo", ".apk", "app-campo", "@"}) {
                if (linha.contains(proibido)) fail("o log levou \"" + proibido + "\": " + linha);
            }
        }
    }

    // ---------------------------------------------------------------- o aviso do download e a tela no fundo

    @Test
    public void atualizarAgoraAvisaQueEstaBaixandoAntesDoDownload() {
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, telas.avisosDeBaixando);
        assertEquals(Arrays.asList("oferta", "baixando", "download", "instalar"), telas.eventos);
        assertEquals(0, telas.avisosDeFalha);
    }

    @Test
    public void depoisNaoAvisaQueEstaBaixando() {
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(0, telas.avisosDeBaixando);
    }

    @Test
    public void shaErradoAvisaUmaVezSo() {
        rede.json = json(12, "1.2.0", APK_CERTO, SHA_ABD, "x");
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(1, telas.avisosDeBaixando);
        assertEquals(1, telas.avisosDeFalha);
    }

    @Test
    public void downloadQueTerminaComATelaNoFundoInstalaNoProximoResume() throws IOException {
        // O técnico foi a outro app enquanto baixava: o Android 10+ bloqueia em silêncio a tela aberta do fundo.
        telas.naFrente = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
        assertEquals(1, telas.instalacoesNoFundo);
        assertNull("o fluxo solta: quem segue é o resume", dono.get());
        assertTrue("o APK conferido fica no cache", new File(pasta, "app-campo.apk").isFile());
        assertEquals("tela no fundo não é falha", 0, telas.avisosDeFalha);

        // Volta ao app (outro processo, se o Android o matou): abre o instalador sem baixar nem ler o versao.json.
        telas.naFrente = true;
        agora.addAndGet(UMA_HORA_MS);
        novo().verificar();
        assertEquals(1, telas.instalados.size());
        assertArrayEquals(bytes("abc"), Files.readAllBytes(telas.instalados.get(0).toPath()));
        assertEquals(1, rede.downloads);
        assertEquals(1, rede.leituras);

        // Uma tentativa: o resume seguinte (a câmera, o instalador que a pessoa cancelou) não reabre.
        novo().verificar();
        assertEquals(1, telas.instalados.size());
    }

    @Test
    public void aRetomadaQueCaiNoFundoDeNovoGuardaOPrazoDaPrimeira() {
        telas.naFrente = false;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar(); // baixou às 0 h, tela no fundo
        agora.addAndGet(5 * UMA_HORA_MS);
        novo().verificar(); // 5 h: retoma, e a tela foi para o fundo de novo antes do instalador
        assertEquals(2, telas.instalacoesNoFundo);
        agora.addAndGet(UMA_HORA_MS);
        telas.naFrente = true;
        telas.resposta = Resposta.ADIAR;
        novo().verificar(); // 6 h depois do download: o prazo é contado da primeira vez, e a pendência venceu
        assertEquals(0, telas.instalados.size());
    }

    @Test
    public void aPendenciaDaPermissaoVenceEmSeisHoras() {
        deixarPendente();
        telas.pode = true;
        agora.addAndGet(SEIS_HORAS_MS);
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals("passadas as 6 h, a pendência não abre o instalador", 0, telas.instalados.size());
        // E não sobra: a checagem normal (as 6 h também passaram) segue, oferece de novo e limpa o cache.
        assertEquals(2, rede.leituras);
        assertEquals(2, telas.ofertas.size());
        assertFalse(new File(pasta, "app-campo.apk").exists());
        novo().verificar();
        assertEquals(0, telas.instalados.size());
    }

    @Test
    public void aPendenciaDaPermissaoValeAteUmMsAntesDasSeisHoras() {
        deixarPendente();
        telas.pode = true;
        agora.addAndGet(SEIS_HORAS_MS - 1);
        novo().verificar();
        assertEquals(1, telas.instalados.size());
    }

    @Test
    public void prazoDaPendencia() {
        long t = 1_800_000_000_000L;
        assertTrue(Atualizador.pendenciaValida(t, t));
        assertTrue(Atualizador.pendenciaValida(t + SEIS_HORAS_MS - 1, t));
        assertFalse(Atualizador.pendenciaValida(t + SEIS_HORAS_MS, t));
        assertFalse("o relógio que voltou não deixa a pendência valer para sempre", Atualizador.pendenciaValida(t - 1, t));
        assertFalse("pendência sem a hora (de uma versão anterior)", Atualizador.pendenciaValida(t, 0));
    }

    @Test
    public void pendenciaSemAHoraNaoInstala() throws IOException {
        // A pendência gravada sem a hora (uma versão de teste anterior da casca) não tem como provar que é recente.
        assertTrue(pasta.mkdirs());
        Files.write(new File(pasta, "app-campo.apk").toPath(), bytes("abc"));
        prefs.edit().putInt("pendente_codigo", 12).putString("pendente_sha256", SHA_ABC).commit();
        telas.resposta = Resposta.ADIAR;
        novo().verificar();
        assertEquals(0, telas.instalados.size());
    }

    // ---------------------------------------------------------------- o build de depuração

    @Test
    public void oBuildDeDepuracaoNaoOfereceARelease() {
        // O APK de depuração tem outra assinatura: o instalador recusaria a Release por cima, e o reflexo de desinstalar
        // apagaria o espelho. Ele nem lê o versao.json.
        depuracao = true;
        telas.resposta = Resposta.ACEITAR;
        novo().verificar();
        assertEquals(0, rede.leituras);
        assertEquals(0, telas.ofertas.size());
        assertNull(dono.get());
        assertEquals("nem grava a hora da checagem", -1, prefs.getLong("ultima_checagem_ms", -1));
    }

    @Test
    public void aTelaDeVerdadeLevaODebugDoBuild() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Atualizador a = new Atualizador(activity);
        // No testDebugUnitTest (o que o CI roda) o DEBUG é verdadeiro, e a tela que ignorasse o build daria falso aqui;
        // num teste do release os dois são falsos, e o teste continua valendo.
        assertEquals(BuildConfig.DEBUG, a.depuracao());
    }

    // ---------------------------------------------------------------- textos e instalador

    @Test
    public void textosExatosDoPlano() {
        Context c = RuntimeEnvironment.getApplication();
        assertEquals("Nova versão do App de Campo", c.getString(R.string.casca_atualizacao_titulo));
        assertEquals("Atualizar agora", c.getString(R.string.casca_atualizacao_atualizar));
        assertEquals("Depois", c.getString(R.string.casca_atualizacao_depois));
        assertEquals("Permitir a atualização", c.getString(R.string.casca_instalar_titulo));
        assertEquals("Para instalar a versão nova, libere Instalar apps desconhecidos para o App de Campo e volte para cá.",
            c.getString(R.string.casca_instalar_texto));
        assertEquals("Abrir ajustes", c.getString(R.string.casca_abrir_ajustes));
        assertEquals("Não consegui conferir o arquivo da atualização. Tento de novo mais tarde.",
            c.getString(R.string.casca_atualizacao_sha_toast));
        assertEquals("Baixando a versão nova…", c.getString(R.string.casca_atualizacao_baixando));
    }

    @Test
    public void textoDaVersaoNovaTemUmaLinhaPorNovidadeComTracoEEspaco() {
        Context c = RuntimeEnvironment.getApplication();
        VersaoRemota v = VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC, "Fotos mais rápidas", "Correção do GPS"));
        assertEquals("Versão 1.2.0. Novidades:\n- Fotos mais rápidas\n- Correção do GPS",
            Atualizador.textoDoDialogo(c, v));
        VersaoRemota sem = VersaoRemota.ler(json(12, "1.2.0", APK_CERTO, SHA_ABC));
        assertEquals("Versão 1.2.0.", Atualizador.textoDoDialogo(c, sem));
    }

    @Test
    public void instaladorEUmVerComUriETipoDoApkEPermissaoDeLeitura() {
        Uri uri = Uri.parse("content://br.com.gridco.campo.arquivos/atualizacao/app-campo.apk");
        Intent i = Atualizador.intentParaInstalar(uri);
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals("application/vnd.android.package-archive", i.getType());
        assertEquals(uri, i.getData());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Test
    public void instaladorRecebeOUriDoProviderParaOApkDaPasta() throws IOException {
        semFileProviderNoWindows();
        Context c = RuntimeEnvironment.getApplication();
        File apk = new File(new File(c.getCacheDir(), "atualizacao"), "app-campo.apk");
        assertTrue(apk.getParentFile().mkdirs() || apk.getParentFile().isDirectory());
        Files.write(apk.toPath(), bytes("abc"));

        Intent i = Atualizador.intentDoInstalador(c, apk);
        assertEquals("content://br.com.gridco.campo.arquivos/atualizacao/app-campo.apk", i.getData().toString());
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals("application/vnd.android.package-archive", i.getType());
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void oProviderDoInstaladorNaoExpoeNadaAlemDeCacheAtualizacao() throws IOException {
        semFileProviderNoWindows();
        Context c = RuntimeEnvironment.getApplication();
        File fora = new File(c.getFilesDir(), "espelho.db");
        Files.write(fora.toPath(), bytes("segredo"));
        try {
            Atualizador.intentDoInstalador(c, fora);
            fail("o provider não devia expor arquivo fora de cache/atualizacao/");
        } catch (IllegalArgumentException esperado) {
            // ok
        }
        File irmao = new File(c.getCacheDir(), "outro.apk");
        Files.write(irmao.toPath(), bytes("x"));
        try {
            Atualizador.intentDoInstalador(c, irmao);
            fail("a pasta de cache em si também não");
        } catch (IllegalArgumentException esperado) {
            // ok
        }
    }

    // ---------------------------------------------------------------- apoio

    // O FileProvider decide se o arquivo está sob uma raiz comparando o caminho com uma barra "/" depois da raiz, o
    // que é certo no Android e no Linux do CI, mas no JVM do Windows o caminho vem com "\" e nenhum arquivo é achado
    // (visto em 17/10/2026: "Failed to find configured root", com o arquivo dentro de cache/atualizacao/). Aqui os dois
    // testes que passam pelo FileProvider são pulados; a configuração dele (autoridade, uma só pasta, não exportado) é
    // conferida em Node, no testes/configuracao.test.mjs, e o CI do Linux roda os dois.
    private static void semFileProviderNoWindows() {
        Assume.assumeTrue("o FileProvider espera caminhos com '/'", File.separatorChar == '/');
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private File arquivo(String conteudo) throws IOException {
        File f = tmp.newFile();
        Files.write(f.toPath(), bytes(conteudo));
        return f;
    }

    private static void recusa(String json, String quando) {
        try {
            VersaoRemota.ler(json);
        } catch (IllegalArgumentException esperado) {
            return;
        }
        fail("devia recusar: " + quando);
    }

    private static String json(int codigo, String nome, String apk, String sha, String... novidades) {
        StringBuilder lista = new StringBuilder("[");
        for (int i = 0; i < novidades.length; i++) {
            if (i > 0) lista.append(',');
            lista.append('"').append(novidades[i]).append('"');
        }
        lista.append(']');
        return "{\"versionCode\":" + codigo + ",\"versionName\":\"" + nome + "\",\"apk\":\"" + apk
            + "\",\"sha256\":\"" + sha + "\",\"novidades\":" + lista + "}";
    }

    private enum Resposta { NADA, ACEITAR, ADIAR }

    /** A rede: devolve o que o teste pôs, grava o "APK" no destino e conta os pedidos. Nunca abre soquete. */
    private final class RedeFalsa implements Atualizador.Rede {
        String json;
        byte[] apk;
        IOException falhaAoLer;
        IOException falhaAoBaixar;
        RuntimeException erroInesperado;
        RuntimeException erroInesperadoAoBaixar;
        String[] sobras = new String[0];
        int leituras;
        int downloads;
        String ultimaUrl;

        @Override
        public String lerVersao() throws IOException {
            leituras++;
            if (falhaAoLer != null) throw falhaAoLer;
            if (erroInesperado != null) throw erroInesperado;
            return json;
        }

        @Override
        public void baixar(String url, File destino) throws IOException {
            downloads++;
            ultimaUrl = url;
            telas.eventos.add("download");
            if (erroInesperadoAoBaixar != null) throw erroInesperadoAoBaixar;
            // Sem a pasta criada por quem baixa isto falha, como o FileOutputStream de verdade.
            Files.write(destino.toPath(), falhaAoBaixar == null ? apk : bytes("so o comeco"));
            for (String sobra : sobras) Files.write(new File(destino.getParentFile(), sobra).toPath(), bytes("lixo"));
            if (falhaAoBaixar != null) throw falhaAoBaixar;
        }
    }

    /** As telas: guardam o que foi mostrado e respondem sozinhas ao diálogo da versão, conforme o teste. */
    private static final class TelasFalsas implements Atualizador.Telas {
        boolean pode = true;
        boolean ajustesAbrem = true;
        boolean naFrente = true;
        int avisosDeBaixando;
        int instalacoesNoFundo;
        final List<String> eventos = new ArrayList<>();
        Resposta resposta = Resposta.NADA;
        final List<VersaoRemota> ofertas = new ArrayList<>();
        final List<File> instalados = new ArrayList<>();
        List<String> nomesNaPastaAoInstalar;
        int avisosDeFalha;
        int pedidosDePermissao;
        int ajustesAbertos;
        boolean encerrou;
        Runnable aceitou;
        Runnable adiou;
        Runnable aoTocar;

        @Override
        public boolean podeInstalar() {
            return pode;
        }

        @Override
        public void oferecer(VersaoRemota v, Runnable aceitou, Runnable adiou) {
            ofertas.add(v);
            eventos.add("oferta");
            this.aceitou = aceitou;
            this.adiou = adiou;
            if (resposta == Resposta.ACEITAR) aceitou.run();
            else if (resposta == Resposta.ADIAR) adiou.run();
        }

        @Override
        public void avisarBaixando() {
            avisosDeBaixando++;
            eventos.add("baixando");
        }

        @Override
        public void avisarFalha() {
            avisosDeFalha++;
        }

        @Override
        public void pedirPermissao(Runnable aoTocar) {
            pedidosDePermissao++;
            this.aoTocar = aoTocar;
        }

        /** A pessoa toca em "Abrir ajustes" no diálogo da permissão. */
        void tocarAbrirAjustes() {
            aoTocar.run();
        }

        @Override
        public boolean abrirAjustes() {
            ajustesAbertos++;
            return ajustesAbrem;
        }

        @Override
        public void instalar(File apk, Runnable foraDaFrente) {
            if (!naFrente) {
                instalacoesNoFundo++;
                foraDaFrente.run();
                return;
            }
            eventos.add("instalar");
            instalados.add(apk);
            String[] nomes = apk.getParentFile().list();
            Arrays.sort(nomes);
            nomesNaPastaAoInstalar = Arrays.asList(nomes);
        }

        @Override
        public void encerrar() {
            encerrou = true;
            // Fechar o diálogo aberto dispara o "dismiss", que vale como "Depois".
            if (adiou != null) adiou.run();
        }
    }
}
