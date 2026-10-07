# Telumia Desktop

![Telumia](https://raw.githubusercontent.com/Pepeu2010/telumia/main/assets/brand/telumia-banner.png)

Cliente nativo Telumia para **PC — Kotlin Multiplatform, Compose e libmpv**, com foco no Brasil, catálogos configuráveis, perfis, biblioteca e reprodução.

Marca e logo próprios. A entrega 0.2.0-alpha.1 acrescenta aliases brasileiros de Iludida, idioma inicial TMDB pt-BR e seleção do corte brasileiro entre fontes de metadados instaladas, preservando os IDs de capítulos. A edição de 78 capítulos exige uma fonte que a ofereça. Fontes que só fornecem 31 episódios não são artificialmente expandidas.

[Downloads e status](https://github.com/Pepeu2010/telumia/releases) · [Especificação e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/TELUMIA.md) · [Roadmap](https://github.com/Pepeu2010/telumia/blob/main/docs/ROADMAP.md).

## Desenvolvimento

O cliente em desenvolvimento inclui avatar pessoal com arquivo local, clipboard, drag-and-drop, recorte, zoom e biblioteca integrada de 64 ilustrações licenciadas. As fotos ficam no aparelho, separadas do cache e dos payloads da conta. O editor permanece aberto quando o salvamento remoto do perfil falha; perfis locais recriados recebem uma nova identidade para evitar herdar fotos de outro perfil.

A release 0.2.1-alpha.1 inclui esses incrementos, com 149 testes selecionados e MSI Windows, incluindo mutações reais de perfis de convidado, proteção de manifestos futuros e downloads GIF com tamanho limitado. [Downloads](https://github.com/Pepeu2010/telumia/releases/tag/v0.2.1-alpha.1).

O código posterior redesenha a seleção de perfis com cards maiores, foco evidente, imagens pessoais, background do perfil destacado, layout adaptativo e estados de carregamento/criação. Passaram 155 testes selecionados e o MSI; quatro capturas de conteúdo foram revisadas de 1366×768 a 4K. A tela conserva as rotas existentes de troca, edição e PIN. Esse redesenho posterior ainda não está nos binários da 0.2.1-alpha.1, e a experiência completa de perfis continua em desenvolvimento. [Evidências e pendências](https://github.com/Pepeu2010/telumia/blob/main/docs/PROFILE_STUDIO.md).

```sh
./gradlew :composeApp:run
```

Use as instruções de configuração local do [projeto central](https://github.com/Pepeu2010/telumia). Não publique arquivos de credenciais ou keystores. A implementação completa continua em execução: a nova marca e a busca Brasil não concluem Profile Studio, cache Auto, timeline, Live TV/EPG, Scene Info ou Phone Remote.

## Licença

[GPL-3.0](LICENSE). Copyrights, autoria e avisos de terceiros preservados. [Créditos do código](FORK_NOTICE.md).
