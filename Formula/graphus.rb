class Graphus < Formula
  desc "Java/Spring code graph and RAG CLI"
  homepage "https://github.com/alcantaraleo/graphus"
  # version and sha256 are updated automatically by publish.yml on each release.
  # Edit the formula logic here; do not hand-edit alcantaraleo/homebrew-graphus.
  version "0.6.0"
  url "https://github.com/alcantaraleo/graphus/releases/download/v#{version}/graphus.jar"
  sha256 "c5d98cee20474132df37df8849f9ea8a20dcb23c9dd43c8c5cfbe9ad2229ec60"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "graphus.jar"

    bin.mkpath
    (bin/"graphus").atomic_write <<~EOS
      #!/bin/sh
      export JAVA_HOME="${JAVA_HOME:-#{Formula["openjdk@21"].opt_prefix}}"
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/graphus.jar" "$@"
    EOS
    chmod 0755, bin/"graphus"
  end

  test do
    output = shell_output("#{bin}/graphus --help")
    assert_match "Java/Spring code graph + RAG CLI", output
    assert_match "install", output
    assert_match "serve", output
  end
end
