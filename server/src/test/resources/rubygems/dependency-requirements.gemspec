Gem::Specification.new do |s|
  s.name = 'kk-dep-probe'
  s.version = '0.1.0'
  s.summary = 'Disposable kkRepo dependency constraint audit'
  s.authors = ['audit']
  s.description = "Example configuration:\nname: example\nplatform: java\nsummary: example\nversion: 9.9.9\n"
  s.files = []
  s.add_dependency 'rake', '~> 13.0'
  s.add_dependency 'json', '~> 2.6'
end
