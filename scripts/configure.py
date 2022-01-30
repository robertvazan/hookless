# This script generates and updates project configuration files.

# We are assuming that project-config is available in sibling directory.
# Checkout from https://github.com/robertvazan/project-config
import os.path
import sys
sys.path.append(os.path.normpath(os.path.join(__file__, '../../../project-config/src')))

from java import *

project_script_path = __file__
repository_name = lambda: 'hookless'
pretty_name = lambda: 'Hookless'
pom_description = lambda: 'Reactive programming library.'
inception_year = lambda: 2015
jdk_version = lambda: 11
stagean_annotations = lambda: True
javadoc_site = lambda: website() + 'javadocs/core/'
complete_javadoc = lambda: False
project_status = lambda: f'{experimental_status()} Core classes are stable, including APIs, though poorly documented.'

def dependencies():
    use_noexception()
    use_fastutil()
    use_guava()
    use('io.micrometer:micrometer-core:1.6.4')
    use('io.opentracing:opentracing-util:0.33.0')
    use_junit()
    use_hamcrest()
    use('org.awaitility:awaitility:4.0.3', 'test')
    use('org.junit-pioneer:junit-pioneer:0.9.0')
    use_slf4j_test()

javadoc_links = lambda: [
    *standard_javadoc_links(),
    'https://noexception.machinezoo.com/javadoc/'
    # No link to OpenTracing, because automatic modules cannot be linked.
]

documentation_links = lambda: [
    *standard_documentation_links(),
    ('Concepts', 'https://hookless.machinezoo.com/concepts'),
    ('Adapters', 'https://hookless.machinezoo.com/adapters')
]

generate(globals())
