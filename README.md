<div align="center">
  
# 📚 HidariNovel

<!-- Badges -->
[![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=java&logoColor=white)]()
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-F2F4F9?style=for-the-badge&logo=spring-boot)]()
[![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

*Uma ferramenta CLI robusta para busca, extração e conversão de web novels para leitura offline.*

</div>

<br>

## 📖 Sobre o Projeto
O **HidariNovel** é uma aplicação de linha de comando (CLI) construída em **Java** projetada para buscar e baixar capítulos de web novels. 

O objetivo principal deste projeto é proporcionar maior facilidade para a **leitura offline** e possibilitar a **geração de arquivos EPUB** para consumo pessoal em e-readers (como Kindle, Kobo, etc). Além disso, o projeto foi concebido como uma excelente **ferramenta de estudo**, focada em técnicas de web scraping, arquitetura de software e desenvolvimento backend usando Java e o ecossistema Spring.

---

## ⚠️ Aviso Importante: Apoie os Criadores
> **A intenção desta ferramenta NÃO é, e nunca será, roubar visualizações (views) ou tráfego dos sites oficiais e dos tradutores.**

Encorajamos fortemente que os usuários sempre **apoiem os criadores de conteúdo, autores e grupos de tradução**, acessando os sites oficiais diretamente, desativando ad-blocks onde apropriado, deixando comentários e contribuindo (doações, patreons) para que eles possam continuar o seu trabalho incrível. 

O uso do **HidariNovel** destina-se estritamente a:
1. **Propósitos de estudo** e desenvolvimento pessoal.
2. **Comodidade e acessibilidade** para ler obras já acompanhadas, em formato EPUB e offline (viagens, áreas sem internet, etc).

---

## ⚙️ Tecnologias Utilizadas
- **[Java](https://www.java.com/)** - Linguagem principal
- **[Spring Boot](https://spring.io/projects/spring-boot)** & **Spring Shell** - Para construção da interface de linha de comando
- **[Playwright](https://playwright.dev/java/) / JSoup** - Para renderização, automação e web scraping
- **[Maven](https://maven.apache.org/)** - Gerenciamento de dependências

---

## 🚀 Como Baixar

Para obter uma cópia funcional em sua máquina local, você pode clonar este repositório utilizando o Git:

```bash
git clone https://github.com/seu-usuario/HidariNovel.git
cd HidariNovel
```

*(Ou clique no botão **Code** no topo da página e selecione **Download ZIP** para extrair manualmente).*

---

## 💻 Como Usar

### Pré-requisitos
- **Java JDK 17+** (ou versão superior correspondente ao projeto)
- **Maven** instalado (opcional caso utilize a wrapper `./mvnw` ou sua IDE)

### Executando Localmente (Desenvolvimento)
Para rodar o projeto e abrir a interface interativa CLI, utilize o comando abaixo na raiz do repositório:

```bash
mvn spring-boot:run
```

### Compilando e Executando (Produção)
Se preferir rodar através do pacote final compilado:

1. Compile o projeto e construa o arquivo `.jar`:
```bash
mvn clean package
```
2. Após o término, execute o arquivo gerado na pasta `target`:
```bash
java -jar target/hidarinovel-1.0.0.jar
```

> 💡 **Dica:** Assim que o terminal interativo do Spring Shell iniciar, digite `help` para ver a lista completa de comandos disponíveis para pesquisar e baixar novels.