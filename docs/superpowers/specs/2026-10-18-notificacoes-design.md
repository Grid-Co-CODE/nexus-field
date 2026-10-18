# Nexus Field: notificações para o técnico (desenho)

18/10/2026. Pedido do dono da casca. Parte do Nexus Field (spec 2026-10-16-app-campo-android-design.md); leia o CLAUDE.md
do repositório antes.

## 1. Intenção

O técnico deve saber, sem abrir o app, o que tem para fazer e quando surge algo urgente:
- **07:00, resumo da manhã:** as tarefas do dia, em uma linha. Exemplo: "Bom dia! Hoje: 4 tarefas, 1 corretiva e 3
  preventivas."
- **13:00, resumo da tarde:** só o que ainda está aberto do dia. Exemplo: "Boa tarde! Faltam 2 tarefas de hoje: 1
  corretiva e 1 preventiva."
- **Emergência, quando surgir:** religamento ou corretiva emergencial aberta no Fracttal para ele chega em até 10 minutos.
  Em dia útil e no expediente, toca alto (som e vibração próprios). Fora disso, chega em silêncio.

## 2. Objetivos, não-objetivos e anti-objetivos

**Objetivos**
- Os dois resumos diários e o aviso de emergência no Nexus Field (Android), mesmo com o app fechado.
- Tocar no resumo abre o app. Tocar na emergência abre o app já na OS.
- Prova de ponta a ponta no aparelho de prova sem criar OS falsa no Fracttal (envio de teste, seção 4.4).

**Não-objetivos**
- iPhone: continua no PWA, sem notificação.
- Notificação para supervisor, PCM ou COS.
- Configuração de horário pelo técnico: 07:00 e 13:00 são fixos.
- Escala de plantão e feriados.

**Anti-objetivos (é fracasso mesmo funcionando)**
- Notificação em dia sem tarefa: o resumo só sai quando há tarefa (decisão do dono).
- Barulho:
  - emergência repetida para a mesma OS;
  - resumo duplicado no mesmo horário;
  - emergência tocando alto fora do expediente.
- Serviço pago, ou chamadas novas que multipliquem a cota do Fracttal (200 por minuto, da empresa inteira).
- Dado pessoal, nome de usina ou de pessoa saindo pelo Firebase (o Google) além do número da OS e das contagens.
- OS falsa no Fracttal para testar: ela entraria nos indicadores do Fracttal e do Nexus.
- Mudança no site feita por esta casca. A parte do servidor é da sessão "App de campo".

## 3. Decisões do dono

- Resumos às 07:00 e às 13:00, curtos. O das 13:00 traz só o que está aberto. Dia sem tarefa não manda nada.
- **Quem monta e manda os resumos é o servidor do App de Campo**, junto com a emergência. O celular só mostra. Assim o
  resumo chega mesmo numa segunda (sem o técnico ter aberto o app no fim de semana) e com o app fechado.
- A emergência nasce no Fracttal (o COS ou o supervisor abre a OS com o técnico responsável) e chega em até 10 minutos.
- A emergência toca alto **só em dia útil e no expediente**. Fora disso, chega em silêncio. O plantão continua pelo
  telefone.
- Prova da emergência por **envio de teste só para administrador**, nunca por OS falsa no Fracttal.

## 4. Como funciona

### 4.1 Um caminho só: o Firebase
- Resumos e emergências saem do servidor do App de Campo pelo **Firebase Cloud Messaging**, que é grátis. É o que o
  Android entrega com o app fechado e o que o Xiaomi não segura.
- Sem sinal na hora, a notificação chega quando o sinal voltar. Risco aceito, seção 6.

### 4.2 A casca (Nexus Field 1.1.0)
- **Permissão de notificação** (Android 13+, `POST_NOTIFICATIONS`): pedida na primeira abertura e na primeira abertura
  depois da atualização, com o motivo explicado. Texto novo vai à prévia.
- **Registro do aparelho:**
  1. com o técnico logado (a página tem o e-mail e o id_token), a casca obtém o token do FCM;
  2. registra o token na rota nova do servidor (4.3), com o Bearer da página, como as outras chamadas;
  3. registra de novo quando o token mudar;
  4. no "sair", a casca avisa o servidor para esquecer o token.
- **Canais** (o técnico vê e pode ajustar nos ajustes do Android):
  - "Resumo do dia": importância padrão;
  - "Emergências": importância alta, som e vibração próprios;
  - "Emergências fora do expediente": silencioso.

  Quem escolhe o canal é o servidor, na mensagem (4.3).
- **Ao tocar:**
  - no resumo, abre o app;
  - na emergência, abre o app e leva à OS pelos meios que a página já tem. A página não abre OS pelo endereço; o caminho
    exato fica para o construtor, preso por teste de contrato.
- O ícone das notificações é o G branco.

### 4.3 O contrato entre a casca e o servidor
- **`POST /api/push/registrar`** com `{token, aparelho:'android', app:'NexusField/<versão>'}` e o Bearer de sempre.
  - O servidor associa o token ao e-mail do token Microsoft e o guarda (tabela do Storage dele).
  - Um técnico pode ter mais de um aparelho.
  - **`POST /api/push/esquecer`** com o mesmo token, no "sair".
- **A mensagem do FCM** é só de dados, com prioridade alta:
  `{tipo:'resumo'|'emergencia', canal:'resumo'|'emergencia'|'emergencia_silenciosa', titulo, texto, os?}`.
  - O texto é curto e sem nome de usina ou de pessoa, por exemplo "Corretiva emergencial: OS 12345". O nome da usina só
    entra se o dono aprovar na prévia, porque o FCM passa pelo Google.
- **A chave de serviço do Firebase** fica só nas App Settings do servidor, como segredo. O `google-services.json` do app
  não é segredo (só identifica o projeto) e entra no repositório da casca.

### 4.4 O servidor (sessão "App de campo")
- **Resumos:**
  - duas rotinas por timer, às 07:00 e às 13:00 (hora de Brasília);
  - para cada técnico com aparelho registrado e com tarefa no dia, monta as contagens com as mesmas regras de "hoje" e
    de "concluída" que a página usa (o dia, os tipos, Finalizado e Em verificação contam como feito);
  - às 13:00, conta só o que está aberto;
  - lê a programação e os estados que o servidor já tem, sem chamada nova ao Fracttal.
- **Emergência:**
  - na conferência do Fracttal que já roda (a cada 5 a 10 minutos), quando surge para um técnico uma OS do tipo
    emergencial (religamento ou corretiva emergencial; o critério exato no Fracttal é dela), manda uma vez por OS;
  - escolhe o canal pelo horário: dia útil das 07:00 às 17:00 toca alto, fora disso vai em silêncio;
  - conferir que essa leitura roda sozinha no expediente, mesmo com os Apps fechados. Se não rodar, avisar antes de pôr
    um timer novo, por causa da cota.
- **Envio de teste:** uma rota só para administrador, que manda um resumo e uma emergência de mentira para os aparelhos
  de quem chamou. Não cria nada no Fracttal.

## 5. Dependências e ordem

1. **Firebase:** o dono cria o projeto grátis com a conta Google da Grid e baixa o `google-services.json`. Recebe o passo
   a passo na hora; criar conta e entrar com senha é com ele. O dono (ou a sessão "App de campo") põe a chave de serviço
   nas App Settings do servidor.
2. **Casca 1.1.0:** a permissão, os canais, o registro do token e a abertura na OS. Ela chega ao aparelho de prova pelo
   aviso de atualização do próprio app, o que também testa a atualização.
3. **Servidor:** a sessão "App de campo" faz as rotas de registro e de teste, os dois timers e o envio na conferência
   do Fracttal. Segue o processo dela: teste, deploy e conferir_deploy.
4. **Prova:**
   1. o envio de teste no aparelho de prova (os dois canais, dentro e fora do expediente);
   2. o resumo das 07:00 e das 13:00 de um dia com tarefa do usuário de teste;
   3. depois, o piloto.

## 6. Riscos aceitos
- Sem sinal às 07:00 ou às 13:00, o resumo chega quando o sinal voltar.
- O FCM passa pelo Google: por isso a mensagem leva só tipo, número da OS e contagens.
- Expediente fixo (dia útil, 07:00 às 17:00), sem feriados nem plantão. Feriado conta como dia útil até alguém pedir a
  tabela.

## 7. O que fica para o construtor
- Como a casca obtém o e-mail e o id_token da página para registrar (pelo canal que já existe), sem guardar token no
  espelho.
- Como abrir a OS a partir da notificação, preso por teste de contrato com o site.
- Os textos exatos (vão à prévia) e o ícone pequeno das notificações.
- Na sessão "App de campo": a tabela dos tokens, o critério de "emergencial" no Fracttal, os timers e o envio do FCM.

## 8. Decisões que foram minhas (para o dono conferir)
- O expediente é dia útil das 07:00 às 17:00, e feriado conta como dia útil.
- A emergência fora do expediente chega num canal silencioso, que não toca, em vez de ser descartada.
- Mensagem sem nome de usina nem de pessoa; o nome da usina só com aprovação na prévia.
- Três canais de notificação, que o técnico pode ajustar nos ajustes do Android.
- Versão 1.1.0 da casca para a parte do celular.
