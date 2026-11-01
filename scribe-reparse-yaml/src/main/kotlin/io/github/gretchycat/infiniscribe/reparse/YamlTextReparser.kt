package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.representer.Representer
import java.util.Collections
import java.util.IdentityHashMap

/** Operates on YAML nodes, retaining tags, aliases and scalar spellings without constructing objects. */
object YamlTextReparser : TextReparser {
    override val name = "YAML"
    override val extensions = setOf("yaml", "yml")
    override val mimeTypes = setOf("application/yaml", "text/yaml", "application/x-yaml", "text/x-yaml")
    override fun format(text: String, cancellation: CancellationToken): String {
        val loader = LoaderOptions().apply {
            isProcessComments = true
            codePointLimit = 4 * 1024 * 1024
            nestingDepthLimit = 64
            maxAliasesForCollections = 50
        }
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isProcessComments = true
            isExplicitStart = true // Each composed document must retain a document boundary.
            indent = 2
            splitLines = false // Android applies word wrapping after reparsing.
        }
        val yaml = Yaml(SafeConstructor(loader), Representer(options), options, loader)
        val out = ReparseWriter(cancellation)
        ReparseReader(text, cancellation).use { reader ->
            for (node in yaml.composeAll(reader)) {
                val seen = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
                val pending = java.util.ArrayDeque<Node>()
                pending.add(node)
                while (pending.isNotEmpty()) {
                    cancellation.check()
                    val next = pending.removeLast()
                    if (!seen.add(next)) continue
                    when (next) {
                        is MappingNode -> {
                            next.flowStyle = DumperOptions.FlowStyle.BLOCK
                            next.value.forEach { pending.add(it.keyNode); pending.add(it.valueNode) }
                        }
                        is SequenceNode -> {
                            next.flowStyle = DumperOptions.FlowStyle.BLOCK
                            next.value.forEach(pending::add)
                        }
                    }
                }
                yaml.serialize(node, out)
            }
        }
        return out.toString()
    }
}
