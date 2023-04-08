# This script generates and updates project configuration files.

# Run this script with rvscaffold in PYTHONPATH
import rvscaffold as scaffold

class Project(scaffold.Java):
    def script_path_text(self): return __file__
    def repository_name(self): return 'hookless'
    def is_member_project(self): return True
    def pretty_name(self): return 'Hookless'
    def pom_description(self): return 'Reactive programming library.'
    def inception_year(self): return 2015
    def jdk_version(self): return 17
    def stagean_annotations(self): return True
    def complete_javadoc(self): return False
    
    def dependencies(self):
        yield from super().dependencies()
        yield self.use_noexception()
        yield self.use_noexception_slf4j()
        yield self.use_fastutil()
        yield self.use_guava()
        yield self.use('io.micrometer:micrometer-core:1.6.4')
        yield self.use('io.opentracing:opentracing-util:0.33.0')
        yield self.use_junit()
        yield self.use_hamcrest()
        yield self.use('org.awaitility:awaitility:4.0.3', 'test')
        yield self.use('org.junit-pioneer:junit-pioneer:0.9.0')
        yield self.use_slf4j_test()
    
    def javadoc_links(self):
        yield from super().javadoc_links()
        yield 'https://noexception.machinezoo.com/javadocs/core/'
        # No link to OpenTracing, because automatic modules cannot be linked.
    
    def documentation_links(self):
        yield from super().documentation_links()
        yield 'Concepts', 'https://hookless.machinezoo.com/concepts'
        yield 'Adapters', 'https://hookless.machinezoo.com/adapters'

Project().generate()
