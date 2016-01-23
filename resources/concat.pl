# Concats a load of .raw bubble files. Once off script.

binmode STDOUT;

for( $i=5; $i < 21; ++$i ) {

  $fname = sprintf( "bubble%02d.raw", $i );
  open( FH, "<$fname") or die "Can't open $fname : $!";
  binmode FH;

  if( read( FH, $buf, $i * $i * 3 ) < $i * $i * 3) {
    die "Too little data in $fname";
  }

  if( read( FH, $tmp, 1 ) ) {
    die "Too much data in $fname";
  }

  $c = sprintf("%c", $i );
  print "$c$buf";
 
  close FH;


}

print sprintf("%c", 0);


